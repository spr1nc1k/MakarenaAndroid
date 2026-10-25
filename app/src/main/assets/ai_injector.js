// ai_injector.js
// Niewykrywalny silnik analizy DOM i podpowiedzi AI dla platformy Testportal
// Tryb przełącznika (Persistent Toggle Mode):
// 2x głośność w dół = WŁĄCZ / WYŁĄCZ (stan utrzymywany na wszystkich kolejnych pytaniach)
// 2x głośność w górę = PANIC MODE (natychmiastowe ukrycie i wyczyszczenie wszystkiego)

(function() {
    'use strict';

    if (window.__makarena_injector_loaded) {
        return;
    }
    window.__makarena_injector_loaded = true;

    let isPanicMode = false;
    let isProcessing = false;
    let currentQuestionSignature = '';
    let cachedOptionElements = [];
    let lastResolvedIndices = [];
    let lastResolvedOpenAnswer = '';

    const originalPlaceholders = new Map();
    const originalValues = new Map();
    const originalTinyMceValues = new Map();
    const styledOptionElements = [];

    function escapeHTML(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // Usunięcie dynamicznych liczników czasu, aby nie fałszować sygnatury pytania
    function sanitizeQuestionText(raw) {
        if (!raw) return '';
        return raw
            .replace(/Pozostały\s+czas[:\s]+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?/gi, '')
            .replace(/Czas\s+na\s+odpowiedź[:\s]+[0-9]{1,2}:[0-9]{2}/gi, '')
            .replace(/\b[0-9]{1,2}:[0-9]{2}\b/g, '')
            .replace(/\s+/g, ' ')
            .trim();
    }

    // Ekstrakcja URL obrazu pytania
    function extractQuestionImageUrl() {
        const img = document.querySelector(
            '.question_essence img, .question_content img, [class*="question"] img, #questionForm img, .question_image img, .test-question-content img'
        );

        if (!img) return null;

        const src = img.src || '';
        if (src.includes('logo') || src.includes('avatar') || src.includes('icon_') || src.includes('data:image/svg')) {
            return null;
        }

        return src;
    }

    // Obliczenie stabilnej sygnatury pytania
    function computeQuestionSignature() {
        const qEl = document.querySelector(
            '.question_essence, .question_text_content, .question_content, .question-text, .question_text, ' +
            '.test-question-content, [class*="question_text"], [class*="question-text"], .question'
        );
        const qRaw = qEl ? (qEl.innerText || qEl.textContent || '') : '';
        const qClean = sanitizeQuestionText(qRaw).slice(0, 300);

        const qIdInput = document.querySelector('input[name="questionId"], input[name="id"], [data-question-id]');
        const qId = qIdInput ? (qIdInput.value || qIdInput.getAttribute('data-question-id') || '') : '';

        const qNumberEl = document.querySelector('.question_number, .question_count, .test-question-number');
        const qNum = qNumberEl ? (qNumberEl.innerText || '').trim() : '';

        return qId + '|' + qNum + '|' + qClean;
    }

    // Główna ekstrakcja danych pytania i wariantów odpowiedzi
    function extractQuestionData() {
        try {
            const questionTextEl = document.querySelector(
                '.question_essence, .question_text_content, .question_content, .question-text, .question_text, ' +
                '.test-question-content, [class*="question_text"], [class*="question-text"], .question, h1, h2, h3'
            ) || document.body;

            const rawText = questionTextEl ? (questionTextEl.innerText || questionTextEl.textContent || '') : '';
            const questionText = sanitizeQuestionText(rawText).slice(0, 1500);

            const imgUrl = extractQuestionImageUrl();

            // Kontener pytań na Testportal
            const answersScope = document.querySelector(
                '.question_answers, #questionForm, .question_content, .test-question-content, .test_content'
            ) || document.body;

            // Detekcja elementów wyboru w obrębie kontenera
            const checkboxes = Array.from(answersScope.querySelectorAll(
                '.mdc-checkbox, input[type="checkbox"], [role="checkbox"], [class*="checkbox"]'
            ));
            const radioInputs = Array.from(answersScope.querySelectorAll(
                '.mdc-radio, input[type="radio"], [role="radio"], [class*="radio"]'
            ));

            // Pytanie otwarte (krótka odpowiedź lub pole edytora TinyMCE)
            const openInput = document.querySelector(
                '#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"], ' +
                '.rich-text-answer-container, .all_short_answers input, .mdc-text-field__input, ' +
                'textarea, input[type="text"]:not([name*="search"]), div[contenteditable="true"]'
            );

            // Wyznaczanie kontenerów poszczególnych opcji odpowiedzi
            let containers = [];
            if (checkboxes.length > 0) {
                containers = checkboxes.map(i => i.closest('.question_answer, .answer_container, label, .question_option_wrapper, .answer_item, tr, li') || i.parentElement || i);
            } else if (radioInputs.length > 0) {
                containers = radioInputs.map(i => i.closest('.question_answer, .answer_container, label, .question_option_wrapper, .answer_item, tr, li') || i.parentElement || i);
            } else if (!openInput) {
                containers = Array.from(document.querySelectorAll(
                    '.question_answers .question_answer, .answer_container, .question_answers .answer, #questionForm .question_option_wrapper, label.answer, .true-false-option, .tf_option'
                ));
            }

            let options = [];
            const resolvedContainers = [];

            containers.forEach((el) => {
                if (!el) return;
                const textEl = el.querySelector('.answer_text, .option_text, label, .mdc-form-field, span') || el;
                let text = textEl.innerText ? textEl.innerText.trim() : textEl.textContent ? textEl.textContent.trim() : '';

                const cleanedText = text.replace(/^[A-Za-z0-9][\.\:\)\-]\s*/, '').trim();
                const displayText = cleanedText.length > 0 ? cleanedText : text;

                const img = el.querySelector('img');
                const optImgUrl = img ? img.src : null;

                if (displayText && displayText.length < 400) {
                    if (!options.some(o => o.text === displayText)) {
                        options.push({ id: options.length, text: displayText, imageUrl: optImgUrl });
                        resolvedContainers.push(el);
                    }
                } else if (img) {
                    const label = 'Opcja ' + (options.length + 1);
                    options.push({ id: options.length, text: label, imageUrl: optImgUrl, isImageOnly: true });
                    resolvedContainers.push(el);
                }
            });

            // Prawda / Fałsz awaryjnie
            if (options.length === 0 && !openInput) {
                const tfKeywords = ['prawda', 'fałsz', 'true', 'false'];
                const allElements = Array.from(answersScope.querySelectorAll('label, div, button, span, td'));
                const tfFound = [];

                allElements.forEach(el => {
                    const t = (el.innerText || '').trim();
                    if (t.length > 0 && t.length < 30) {
                        const lower = t.toLowerCase();
                        if (tfKeywords.some(kw => lower === kw)) {
                            if (!tfFound.some(o => o.text === t || o.el.contains(el))) {
                                tfFound.push({ text: t, el: el });
                            }
                        }
                    }
                });

                if (tfFound.length >= 2) {
                    tfFound.forEach(item => {
                        options.push({ id: options.length, text: item.text });
                        resolvedContainers.push(item.el);
                    });
                }
            }

            cachedOptionElements = resolvedContainers;

            // Rozróżnienie pytań:
            // Kwadrat / Checkbox -> Wielokrotny wybór ('multiple')
            // Kółko / Radio -> Jednokrotny wybór ('closed')
            // Edytor / Pole tekstowe -> Otwarte ('open')
            let type = 'unknown';

            const hasCheckboxes = checkboxes.length > 0 || answersScope.querySelector('.mdc-checkbox, input[type="checkbox"], [role="checkbox"]') !== null;
            const hasRadios = radioInputs.length > 0 || answersScope.querySelector('.mdc-radio, input[type="radio"], [role="radio"]') !== null;

            if (hasCheckboxes) {
                type = 'multiple';
            } else if (hasRadios || options.length >= 2) {
                type = 'closed';
            } else if (openInput || options.length === 0) {
                type = 'open';
                options = [];
            } else if (options.length === 1) {
                type = 'closed';
            }

            return {
                type: type,
                question: questionText,
                options: options,
                imageUrl: imgUrl
            };
        } catch (e) {
            return null;
        }
    }

    // Bezpieczne, niedestrukcyjne wyróżnienie poprawnej opcji
    function highlightOptionElement(el) {
        if (!el) return;

        try {
            el.style.setProperty('font-weight', '700', 'important');
            el.style.setProperty('box-shadow', 'inset 4px 0 0 #00c853, 0 1px 3px rgba(0,200,83,0.15)', 'important');
            el.style.setProperty('background', 'rgba(0, 200, 83, 0.08)', 'important');
            el.style.setProperty('border-radius', '4px', 'important');
            el.setAttribute('data-makarena-active', '1');

            const textEl = el.querySelector('.answer_text, .option_text, label, .mdc-form-field, span');
            if (textEl && textEl !== el) {
                textEl.style.setProperty('font-weight', '800', 'important');
                textEl.style.setProperty('color', '#00701a', 'important');
            }

            const optImg = el.querySelector('img') || (el.tagName === 'IMG' ? el : null);
            if (optImg) {
                optImg.style.setProperty('outline', '3px solid #00c853', 'important');
                optImg.style.setProperty('box-shadow', '0 0 10px rgba(0, 200, 83, 0.7)', 'important');
                optImg.style.setProperty('border-radius', '6px', 'important');
            }

            if (!styledOptionElements.includes(el)) {
                styledOptionElements.push(el);
            }
        } catch(e) {}
    }

    // Cofnięcie wszystkich modyfikacji (zamknięte i otwarte)
    function revertModifications() {
        styledOptionElements.forEach(el => {
            if (!el) return;
            try {
                el.style.removeProperty('font-weight');
                el.style.removeProperty('box-shadow');
                el.style.removeProperty('background');
                el.style.removeProperty('border-radius');
                el.removeAttribute('data-makarena-active');

                const textEl = el.querySelector('.answer_text, .option_text, label, .mdc-form-field, span');
                if (textEl && textEl !== el) {
                    textEl.style.removeProperty('font-weight');
                    textEl.style.removeProperty('color');
                }

                const optImg = el.querySelector('img') || (el.tagName === 'IMG' ? el : null);
                if (optImg) {
                    optImg.style.removeProperty('outline');
                    optImg.style.removeProperty('box-shadow');
                    optImg.style.removeProperty('border-radius');
                }
            } catch(e) {}
        });
        styledOptionElements.length = 0;

        // Przywrócenie pierwotnych wartości w polach otwartych (czyszczenie asysty)
        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]), div[contenteditable="true"]'
        );
        inputs.forEach(input => {
            try {
                if (input._lastInsertedAnswer && input.value === input._lastInsertedAnswer) {
                    const origVal = originalValues.get(input) || '';
                    input.value = origVal;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }
                input._lastInsertedAnswer = null;

                if (originalPlaceholders.has(input)) {
                    input.setAttribute('placeholder', originalPlaceholders.get(input));
                    input.placeholder = originalPlaceholders.get(input);
                } else {
                    input.removeAttribute('placeholder');
                }
            } catch(e) {}
        });
        originalValues.clear();
        originalPlaceholders.clear();

        // Przywrócenie TinyMCE
        const iframe = document.querySelector('#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"]');
        if (iframe) {
            try {
                const ifrDoc = iframe.contentDocument || iframe.contentWindow.document;
                if (ifrDoc && ifrDoc.body) {
                    if (iframe._lastInsertedAnswer && ifrDoc.body.innerText.trim() === iframe._lastInsertedAnswer) {
                        ifrDoc.body.innerHTML = originalTinyMceValues.get(iframe) || '';
                    }
                    iframe._lastInsertedAnswer = null;
                }
            } catch(e) {}
        }
        originalTinyMceValues.clear();
    }

    // Aplikowanie podpowiedzi dla pytań jedno- i wielokrotnego wyboru
    function applyClosedHint(indicesArg) {
        if (isPanicMode) return;
        if (!window.__makarena_ai_active) return;

        revertModifications();

        let indices = [];
        if (Array.isArray(indicesArg)) {
            indices = indicesArg;
        } else if (typeof indicesArg === 'number') {
            indices = [indicesArg];
        }

        lastResolvedIndices = indices;
        if (indices.length === 0) return;

        let targets = cachedOptionElements;
        if (!targets || targets.length === 0) {
            const checkboxes = Array.from(document.querySelectorAll('.mdc-checkbox, input[type="checkbox"]'));
            const radioInputs = Array.from(document.querySelectorAll('.mdc-radio, input[type="radio"]'));
            if (checkboxes.length > 0) {
                targets = checkboxes.map(i => i.closest('.question_answer, .answer_container, label, .question_option_wrapper, tr, li') || i.parentElement || i);
            } else if (radioInputs.length > 0) {
                targets = radioInputs.map(i => i.closest('.question_answer, .answer_container, label, .question_option_wrapper, tr, li') || i.parentElement || i);
            } else {
                targets = Array.from(document.querySelectorAll(
                    '.question_answers .question_answer, .answer_container, .question_option_wrapper, label.answer, .answer_item, [class*="answer"]'
                ));
            }
        }

        indices.forEach(idx => {
            if (targets && targets[idx]) {
                highlightOptionElement(targets[idx]);
            }
        });
    }

    // Aplikowanie podpowiedzi dla pytań otwartych — BEZ ŻADNYCH DUSZKÓW I BEZ KLIKANIA:
    // Odpowiedź trafia bezpośrednio w pole tekstowe / edytor natychmiast po włączeniu asysty
    function applyOpenHint(suggestedAnswer) {
        if (isPanicMode || !suggestedAnswer) return;
        if (!window.__makarena_ai_active) return;

        const clean = String(suggestedAnswer).trim();
        if (clean.length === 0) return;

        lastResolvedOpenAnswer = clean;

        // 1. Obsługa pól standardowych (input, textarea, div editable)
        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]), div[contenteditable="true"]'
        );
        inputs.forEach(input => {
            try {
                if (!originalPlaceholders.has(input)) {
                    originalPlaceholders.set(input, input.getAttribute('placeholder') || '');
                }
                if (!originalValues.has(input)) {
                    originalValues.set(input, input.value || '');
                }

                input.placeholder = clean;
                input.setAttribute('placeholder', clean);

                // Bezpośrednie wpisanie gotowej odpowiedzi w pole (jeśli puste lub poprzednia sugestia)
                if (!input.value || input.value === input._lastInsertedAnswer) {
                    input.value = clean;
                    input._lastInsertedAnswer = clean;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }
            } catch(e) {}
        });

        // 2. Obsługa edytora TinyMCE iframe (#givenAnswer_ifr)
        const iframe = document.querySelector('#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"]');
        if (iframe) {
            try {
                const ifrDoc = iframe.contentDocument || iframe.contentWindow.document;
                if (ifrDoc && ifrDoc.body) {
                    if (!originalTinyMceValues.has(iframe)) {
                        originalTinyMceValues.set(iframe, ifrDoc.body.innerHTML || '');
                    }
                    const curText = ifrDoc.body.innerText.trim();
                    if (!curText || curText === iframe._lastInsertedAnswer) {
                        ifrDoc.body.innerHTML = '<p>' + escapeHTML(clean) + '</p>';
                        iframe._lastInsertedAnswer = clean;
                    }
                }
            } catch(e) {}
        }
    }

    // Panic Mode — całkowite, natychmiastowe ukrycie wszystkich oznaczeń
    function enablePanicMode() {
        isPanicMode = true;
        window.__makarena_ai_active = false;
        lastResolvedIndices = [];
        lastResolvedOpenAnswer = '';
        revertModifications();
    }

    function disablePanicMode() {
        isPanicMode = false;
        window.__makarena_ai_active = true;
    }

    // Automatyczne wykrycie i rozwiązanie nowego pytania w trybie ciągłym (Persistent Toggle Mode)
    function checkAndAutoAnalyzeNewQuestion() {
        if (isPanicMode) return;
        if (!window.__makarena_ai_active) return;
        if (isProcessing) return;

        const newSignature = computeQuestionSignature();
        if (!newSignature || newSignature === currentQuestionSignature) {
            return;
        }

        isProcessing = true;
        try {
            currentQuestionSignature = newSignature;
            lastResolvedIndices = [];
            lastResolvedOpenAnswer = '';
            revertModifications();

            const qData = extractQuestionData();
            if (qData && qData.question) {
                window.prompt('__makarena_bridge:' + JSON.stringify({
                    action: 'questionDataExtracted',
                    data: qData
                }), '');
            }
        } finally {
            isProcessing = false;
        }
    }

    // Strażnik stylów (Style Guardian) — zapobiega znikaniu podświetlenia przy cyklicznym re-renderowaniu DOM przez Testportal
    function guardianTick() {
        if (isPanicMode || !window.__makarena_ai_active) return;

        // Sprawdzenie pytań zamkniętych
        if (lastResolvedIndices && lastResolvedIndices.length > 0) {
            let needsReapply = false;
            lastResolvedIndices.forEach(idx => {
                const el = cachedOptionElements[idx];
                if (el && !el.hasAttribute('data-makarena-active')) {
                    needsReapply = true;
                }
            });

            if (needsReapply) {
                applyClosedHint(lastResolvedIndices);
            }
        }

        // Sprawdzenie pytań otwartych
        if (lastResolvedOpenAnswer) {
            const inputs = document.querySelectorAll('.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"])');
            let needsReapplyOpen = false;
            inputs.forEach(input => {
                if (!input.value && !input.getAttribute('placeholder')) {
                    needsReapplyOpen = true;
                }
            });
            if (needsReapplyOpen) {
                applyOpenHint(lastResolvedOpenAnswer);
            }
        }
    }

    // Obserwator zmian DOM (przejścia SPA / AJAX między pytaniami)
    const mutationObserver = new MutationObserver(function() {
        if (isPanicMode || !window.__makarena_ai_active) return;
        checkAndAutoAnalyzeNewQuestion();
        guardianTick();
    });

    function startObserving() {
        if (document.body) {
            mutationObserver.observe(document.body, { childList: true, subtree: true });
        } else {
            document.addEventListener('DOMContentLoaded', function() {
                if (document.body) {
                    mutationObserver.observe(document.body, { childList: true, subtree: true });
                }
            });
        }
    }
    startObserving();

    // Ciągły strażnik z interwałem 350ms
    setInterval(function() {
        if (isPanicMode || !window.__makarena_ai_active) return;
        checkAndAutoAnalyzeNewQuestion();
        guardianTick();
    }, 350);

    // Nasłuch zdarzeń sterowania z natywnego kodu Kotlin (2x głośność w dół = toggle, 2x w górę = panic)
    document.addEventListener('__makarena_action', function(e) {
        if (!e || !e.detail) return;
        const action = e.detail.action;

        if (action === 'enable' || action === 'analyze') {
            disablePanicMode();
            currentQuestionSignature = '';
            checkAndAutoAnalyzeNewQuestion();
        } else if (action === 'disable' || action === 'panic') {
            enablePanicMode();
        } else if (action === 'applyClosed') {
            if (isPanicMode || !window.__makarena_ai_active) return;
            const indices = e.detail.correctIndices !== undefined ? e.detail.correctIndices : [e.detail.correctIndex];
            applyClosedHint(indices);
        } else if (action === 'applyOpen') {
            if (isPanicMode || !window.__makarena_ai_active) return;
            applyOpenHint(e.detail.answerText);
        }
    });

    // Inicjalizacja przy załadowaniu strony
    setTimeout(function() {
        if (window.__makarena_ai_active && !isPanicMode) {
            checkAndAutoAnalyzeNewQuestion();
        }
    }, 300);
})();
