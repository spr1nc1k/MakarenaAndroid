// ai_injector.js
// Niewykrywalny silnik analizy DOM i podpowiedzi AI dla platformy Testportal
// Tryb przełącznika (Persistent Toggle Mode) sterowany klawiszami głośności
// Wyłącznie POGRUBIANIE PIERWSZEJ LITERY — brak ramek, kropek, cieni czy obramowań

(function() {
    'use strict';

    function sendLog(type, data) {
        try {
            window.prompt('__makarena_log:' + JSON.stringify({
                type: type,
                data: Object.assign({
                    timestamp: Date.now(),
                    url: window.location.href
                }, data || {})
            }), '');
        } catch (_) {}
    }

    sendLog('injector_script_loaded', { href: window.location.href });

    let isPanicMode = false;
    let isProcessing = false;
    let lastQuestionSignature = '';
    let cachedOptionElements = [];
    const originalPlaceholders = new Map();
    const modifiedNodes = [];

    function escapeHTML(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function extractQuestionImageUrl() {
        const img = document.querySelector(
            '.question_essence img, .question_content img, [class*="question"] img, #questionForm img, .question_image img'
        );
        if (!img) return null;
        const src = img.src || '';
        if (src.includes('logo') || src.includes('avatar') || src.includes('icon_')) {
            return null;
        }
        return src;
    }

    function extractQuestionData() {
        // 1. Treść pytania (wyłącznie dedykowane kontenery pytań Testportal)
        const questionTextEl = document.querySelector(
            '.question_essence, .question_text_content, .question_content, .question-text, .question_text, ' +
            '.test-question-content, [class*="question_text"], [class*="question-text"], .question'
        );

        let questionText = '';
        if (questionTextEl) {
            questionText = questionTextEl.innerText ? questionTextEl.innerText.trim() : (questionTextEl.textContent ? questionTextEl.textContent.trim() : '');
        }

        // 2. URL obrazu
        const imgUrl = extractQuestionImageUrl();

        // 3. Wejścia wyboru (MDC / radio / checkbox)
        const checkboxes = Array.from(document.querySelectorAll(
            '#questionForm input[type="checkbox"], input[type="checkbox"]'
        ));
        const radioInputs = Array.from(document.querySelectorAll(
            '#questionForm input[type="radio"], input[type="radio"]'
        ));

        // Pytanie otwarte
        const openInput = document.querySelector(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]), #givenAnswer_ifr, div[contenteditable="true"]'
        );

        // Kontenery opcji
        let containers = [];
        if (checkboxes.length > 0) {
            containers = checkboxes.map(i => i.closest('.answer_container, label, .question_option_wrapper, .answer_item, tr, li') || i.parentElement || i);
        } else if (radioInputs.length > 0) {
            containers = radioInputs.map(i => i.closest('.answer_container, label, .question_option_wrapper, .answer_item, tr, li') || i.parentElement || i);
        } else if (!openInput) {
            containers = Array.from(document.querySelectorAll(
                '.answer_container, .question_answers .answer, #questionForm .question_option_wrapper, label.answer, .true-false-option, .tf_option, [class*="answer_item"]'
            ));
        }

        let options = [];
        const resolvedContainers = [];

        containers.forEach((el) => {
            let text = el.innerText ? el.innerText.trim() : (el.textContent ? el.textContent.trim() : '');
            const img = el.querySelector('img');
            const optImgUrl = img ? img.src : null;

            if (text && text.length > 0 && text.length < 500) {
                if (!options.some(o => o.text === text)) {
                    options.push({ id: options.length, text: text, imageUrl: optImgUrl });
                    resolvedContainers.push(el);
                }
            } else if (img) {
                const label = 'Opcja ' + (options.length + 1);
                options.push({ id: options.length, text: label, imageUrl: optImgUrl, isImageOnly: true });
                resolvedContainers.push(el);
            }
        });

        // Specjalna detekcja dla pytań Prawda / Fałsz
        if (options.length === 0 && !openInput) {
            const tfKeywords = ['prawda', 'fałsz', 'true', 'false'];
            const allElements = Array.from(document.querySelectorAll('label, div, button, span, td'));
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

        let type = 'unknown';
        const isMultiple = (checkboxes.length > 0 && options.length > 0) || document.querySelector('.mdc-checkbox') !== null;

        if (isMultiple) {
            type = 'multiple';
        } else if (options.length >= 2 || (radioInputs.length > 0 && options.length > 0)) {
            type = 'closed';
        } else if (openInput) {
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
    }

    // Pogrubianie WYŁĄCZNIE pierwszej litery lub prefiksu (A., B., 1.) — brak obramowania, brak kropek
    function boldFirstLetterOfElement(targetElement) {
        if (!targetElement) return;
        if (targetElement.getAttribute('data-makarena-marked')) return;

        isProcessing = true;
        try {
            // 1. Sprawdzamy czy istnieje bezpośredni span z literą opcji (A., B., 1., itp.)
            const letterSpan = targetElement.querySelector('.option_letter, .answer_letter, .letter, [class*="letter"]');
            if (letterSpan) {
                const orig = letterSpan.innerText || letterSpan.textContent || '';
                if (orig.trim().length > 0) {
                    letterSpan.innerHTML = '<strong style="font-weight: 900; font-size: 1.15em; color: inherit;">' + escapeHTML(orig) + '</strong>';
                    modifiedNodes.push({ element: letterSpan, originalHTML: orig });
                    targetElement.setAttribute('data-makarena-marked', '1');
                    return;
                }
            }

            // 2. Przeszukujemy węzły tekstowe w kontenerze odpowiedzi
            const walker = document.createTreeWalker(targetElement, NodeFilter.SHOW_TEXT, null, false);
            let node = walker.nextNode();
            while (node) {
                const text = node.nodeValue;
                if (text && text.trim().length > 0) {
                    const trimmed = text.trimStart();
                    const leadingWhitespace = text.slice(0, text.length - trimmed.length);

                    const match = trimmed.match(/^([A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż0-9][\)\.\:\-]?)/);
                    let boldLength = 1;
                    if (match && match[1]) {
                        boldLength = match[1].length;
                    }

                    const firstPart = trimmed.slice(0, boldLength);
                    const restPart = trimmed.slice(boldLength);

                    const span = document.createElement('span');
                    span.innerHTML = leadingWhitespace + '<strong style="font-weight: 900; font-size: 1.15em; color: inherit;">' + escapeHTML(firstPart) + '</strong>' + escapeHTML(restPart);

                    if (node.parentNode) {
                        const parentNode = node.parentNode;
                        parentNode.replaceChild(span, node);
                        modifiedNodes.push({ parent: parentNode, newChild: span, originalNode: node });
                    }
                    targetElement.setAttribute('data-makarena-marked', '1');
                    break;
                }
                node = walker.nextNode();
            }
        } catch (err) {
            sendLog('bold_error', { error: err.message });
        } finally {
            isProcessing = false;
        }
    }

    function revertModifications() {
        isProcessing = true;
        try {
            modifiedNodes.forEach(item => {
                if (item.element && item.originalHTML !== undefined) {
                    item.element.innerHTML = item.originalHTML;
                } else if (item.parent && item.newChild && item.originalNode) {
                    try {
                        item.parent.replaceChild(item.originalNode, item.newChild);
                    } catch (_) {}
                }
            });
            modifiedNodes.length = 0;
            document.querySelectorAll('[data-makarena-marked]').forEach(el => el.removeAttribute('data-makarena-marked'));
        } finally {
            isProcessing = false;
        }
    }

    function applyClosedHint(indicesArg) {
        if (isPanicMode) return;

        revertModifications();

        let indices = [];
        if (Array.isArray(indicesArg)) {
            indices = indicesArg;
        } else if (typeof indicesArg === 'number') {
            indices = [indicesArg];
        }

        if (indices.length === 0) return;

        let targets = cachedOptionElements;
        if (!targets || targets.length === 0) {
            const checkboxes = Array.from(document.querySelectorAll('input[type="checkbox"]'));
            const radioInputs = Array.from(document.querySelectorAll('input[type="radio"]'));
            if (checkboxes.length > 0) {
                targets = checkboxes.map(i => i.closest('.answer_container, label, .question_option_wrapper, tr, li') || i.parentElement || i);
            } else if (radioInputs.length > 0) {
                targets = radioInputs.map(i => i.closest('.answer_container, label, .question_option_wrapper, tr, li') || i.parentElement || i);
            } else {
                targets = Array.from(document.querySelectorAll(
                    '.answer_container, .question_option_wrapper, label.answer, .answer_item, .true-false-option, [class*="answer"]'
                ));
            }
        }

        indices.forEach(idx => {
            if (targets && targets[idx]) {
                boldFirstLetterOfElement(targets[idx]);
                sendLog('closed_hint_applied', { index: idx });
            }
        });
    }

    function insertAnswerIntoEditor(text) {
        if (!text) return;

        // 1. TinyMCE API (Global or activeEditor)
        try {
            if (window.tinymce && window.tinymce.activeEditor) {
                window.tinymce.activeEditor.setContent('<p>' + escapeHTML(text) + '</p>');
                window.tinymce.activeEditor.fire('change');
                window.tinymce.activeEditor.fire('input');
            }
        } catch(_) {}

        // 2. TinyMCE Iframe DOM
        try {
            const iframes = document.querySelectorAll(
                '#givenAnswer_ifr, iframe[id*="tinymce"], iframe.tox-edit-area__iframe, .tox-edit-area iframe'
            );
            iframes.forEach(ifr => {
                try {
                    const doc = ifr.contentDocument || (ifr.contentWindow && ifr.contentWindow.document);
                    if (doc && doc.body) {
                        doc.body.innerHTML = '<p>' + escapeHTML(text) + '</p>';
                        doc.body.dispatchEvent(new Event('input', { bubbles: true }));
                        doc.body.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                } catch(_) {}
            });
        } catch(_) {}

        // 3. Native Textareas and Input Fields
        try {
            const inputs = document.querySelectorAll(
                '#givenAnswer, textarea, .mdc-text-field__input, .all_short_answers input, input[type="text"]:not([name*="search"])'
            );
            inputs.forEach(inp => {
                inp.value = text;
                inp.dispatchEvent(new Event('input', { bubbles: true }));
                inp.dispatchEvent(new Event('change', { bubbles: true }));
            });
        } catch(_) {}

        // 4. Contenteditable Divs
        try {
            const editableDivs = document.querySelectorAll('div[contenteditable="true"]');
            editableDivs.forEach(div => {
                div.innerText = text;
                div.dispatchEvent(new Event('input', { bubbles: true }));
                div.dispatchEvent(new Event('change', { bubbles: true }));
            });
        } catch(_) {}
    }

    function removeOpenHintCard() {
        const cards = document.querySelectorAll('.makarena-open-card');
        cards.forEach(c => c.remove());
    }

    function applyOpenHint(suggestedAnswer) {
        if (isPanicMode || !suggestedAnswer) return;

        const clean = suggestedAnswer.trim();
        const lower = clean.toLowerCase();
        if (lower.includes('404') || lower.includes('not found') || lower.includes('free limit') || lower.includes('error') || lower.includes('too many')) {
            return;
        }

        // Remove any previous card
        removeOpenHintCard();

        // 1. If standard visible text input, set placeholder
        const textInputs = document.querySelectorAll('.mdc-text-field__input, .all_short_answers input, input[type="text"]:not([name*="search"])');
        textInputs.forEach(input => {
            if (input.offsetWidth > 40) {
                if (!originalPlaceholders.has(input)) {
                    originalPlaceholders.set(input, input.getAttribute('placeholder') || '');
                }
                input.setAttribute('placeholder', clean);
            }
        });

        // 2. Render Sleek Floating Stealth Assist Card
        const card = document.createElement('div');
        card.className = 'makarena-open-card';
        card.setAttribute('data-makarena-marked', 'true');
        card.style.cssText = [
            'position: fixed',
            'bottom: 12px',
            'left: 12px',
            'right: 12px',
            'max-width: 440px',
            'margin: 0 auto',
            'background: rgba(15, 23, 42, 0.94)',
            'backdrop-filter: blur(12px)',
            '-webkit-backdrop-filter: blur(12px)',
            'border: 1px solid rgba(56, 189, 248, 0.4)',
            'border-radius: 12px',
            'box-shadow: 0 10px 25px -5px rgba(0,0,0,0.6), 0 0 12px rgba(56, 189, 248, 0.2)',
            'padding: 10px 14px',
            'z-index: 2147483647',
            'font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
            'color: #f8fafc',
            'transition: opacity 0.2s ease, transform 0.2s ease',
            'box-sizing: border-box'
        ].join(';');

        // Header
        const header = document.createElement('div');
        header.style.cssText = 'display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px;';

        const titleBox = document.createElement('div');
        titleBox.style.cssText = 'display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 700; color: #38bdf8;';
        titleBox.innerHTML = '<span style="width: 7px; height: 7px; background: #38bdf8; border-radius: 50%; box-shadow: 0 0 6px #38bdf8; display: inline-block;"></span> Odpowiedź AI';

        const btnBox = document.createElement('div');
        btnBox.style.cssText = 'display: flex; align-items: center; gap: 8px;';

        const insertBtn = document.createElement('button');
        insertBtn.innerText = 'Wstaw do pola';
        insertBtn.style.cssText = [
            'background: linear-gradient(135deg, #0284c7, #0369a1)',
            'color: #ffffff',
            'border: none',
            'border-radius: 6px',
            'padding: 4px 10px',
            'font-size: 11px',
            'font-weight: 700',
            'cursor: pointer',
            'outline: none',
            'box-shadow: 0 2px 6px rgba(2, 132, 199, 0.4)'
        ].join(';');

        insertBtn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            insertAnswerIntoEditor(clean);
            insertBtn.innerText = '✓ Wstawiono!';
            insertBtn.style.background = '#10b981';
            setTimeout(function() {
                if (insertBtn) {
                    insertBtn.innerText = 'Wstaw do pola';
                    insertBtn.style.background = 'linear-gradient(135deg, #0284c7, #0369a1)';
                }
            }, 1800);
            sendLog('open_hint_inserted', {});
        });

        const closeBtn = document.createElement('button');
        closeBtn.innerHTML = '&times;';
        closeBtn.style.cssText = 'background: transparent; color: #94a3b8; border: none; font-size: 18px; line-height: 1; cursor: pointer; padding: 0 4px;';
        closeBtn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            card.remove();
        });

        btnBox.appendChild(insertBtn);
        btnBox.appendChild(closeBtn);
        header.appendChild(titleBox);
        header.appendChild(btnBox);

        // Content
        const content = document.createElement('div');
        content.style.cssText = [
            'font-size: 12.5px',
            'line-height: 1.45',
            'color: #e2e8f0',
            'max-height: 120px',
            'overflow-y: auto',
            'user-select: text',
            '-webkit-user-select: text',
            'word-break: break-word',
            'white-space: pre-wrap'
        ].join(';');
        content.innerText = clean;

        card.appendChild(header);
        card.appendChild(content);

        document.body.appendChild(card);
        sendLog('open_hint_card_rendered', { length: clean.length });
    }

    function enablePanicMode() {
        isPanicMode = true;
        revertModifications();
        removeOpenHintCard();

        const inputs = document.querySelectorAll('.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"], div[contenteditable="true"]');
        inputs.forEach(input => {
            if (originalPlaceholders.has(input)) {
                input.setAttribute('placeholder', originalPlaceholders.get(input));
            } else {
                input.setAttribute('placeholder', 'Wprowadź odpowiedź');
            }
        });
        originalPlaceholders.clear();
        sendLog('panic_mode_enabled', {});
    }

    function disablePanicMode() {
        isPanicMode = false;
    }

    function checkAndAutoAnalyzeNewQuestion() {
        if (isPanicMode) return;
        if (isProcessing) return;

        const qData = extractQuestionData();
        if (!qData || !qData.question || qData.question.length < 3) return;

        // Odrzucanie ekranów końcowych, podsumowań i stron błędów
        const lowerQ = qData.question.toLowerCase();
        const nonQuestionPhrases = [
            'zachowaj wynik',
            'strona internetowa jest niedostępna',
            'koniec testu',
            'wynik testu',
            'zaloguj się',
            'testportal',
            'błąd połączenia',
            'kod testu'
        ];
        if (nonQuestionPhrases.some(phrase => lowerQ.includes(phrase))) {
            return;
        }

        // Pytanie zamknięte musi posiadać co najmniej 2 warianty odpowiedzi
        if ((qData.type === 'closed' || qData.type === 'multiple') && (!qData.options || qData.options.length < 2)) {
            return;
        }

        const sig = qData.question + '||' + qData.options.map(o => o.text).join('|');
        if (sig !== lastQuestionSignature) {
            lastQuestionSignature = sig;
            removeOpenHintCard();
            revertModifications();
            sendLog('question_detected_in_dom', {
                type: qData.type,
                questionPreview: qData.question.slice(0, 120),
                optionsCount: qData.options ? qData.options.length : 0,
                optionsPreview: qData.options.map(o => o.text.slice(0, 40))
            });
            window.prompt('__makarena_bridge:' + JSON.stringify({
                action: 'questionDataExtracted',
                data: qData
            }), '');
        }
    }

    // Obserwator mutacji drzewa DOM dla płynnych przejść SPA
    const mutationObserver = new MutationObserver(function() {
        if (isPanicMode) return;
        if (isProcessing) return;
        checkAndAutoAnalyzeNewQuestion();
    });

    if (document.body) {
        mutationObserver.observe(document.body, { childList: true, subtree: true });
    } else {
        document.addEventListener('DOMContentLoaded', function() {
            if (document.body) {
                mutationObserver.observe(document.body, { childList: true, subtree: true });
            }
        });
    }

    // Ciągły ticker sprawdzający nowe pytania co 450ms
    setInterval(function() {
        if (isPanicMode) return;
        checkAndAutoAnalyzeNewQuestion();
    }, 450);

    // Nasłuch zdarzeń od natywnego środowiska Android (Kotlin)
    document.addEventListener('__makarena_action', function(e) {
        if (!e || !e.detail) return;
        const action = e.detail.action;

        if (action === 'analyze') {
            disablePanicMode();
            checkAndAutoAnalyzeNewQuestion();
        } else if (action === 'applyClosed') {
            if (isPanicMode) return;
            const indices = e.detail.correctIndices !== undefined ? e.detail.correctIndices : [e.detail.correctIndex];
            applyClosedHint(indices);
        } else if (action === 'applyOpen') {
            if (isPanicMode) return;
            applyOpenHint(e.detail.answerText);
        } else if (action === 'panic') {
            enablePanicMode();
        }
    });

    setTimeout(checkAndAutoAnalyzeNewQuestion, 300);
})();
