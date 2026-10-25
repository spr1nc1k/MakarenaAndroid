// ai_injector.js
// Niewykrywalny silnik analizy DOM i podpowiedzi AI dla platformy Testportal
// Tryb przełącznika (Persistent Toggle Mode):
// 2x głośność w dół = WŁĄCZENIE (pozostaje aktywne na wszystkich kolejnych pytaniach)
// 2x głośność w górę = WYŁĄCZENIE (Panic Mode, ukrycie i zatrzymanie asysty)

(function() {
    'use strict';

    let isPanicMode = false;

    let isProcessing = false;
    let currentQuestionText = '';
    let cachedOptionElements = [];
    const originalPlaceholders = new Map();
    const modifiedNodes = [];
    const activeGhostOverlays = [];

    function escapeHTML(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // Ekstrakcja URL obrazu pytania (przesyłamy wyłącznie URL, bajty pobiera Swift natywnie bez ograniczeń CORS)
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

    // Główna ekstrakcja danych pytania i wariantów odpowiedzi
    function extractQuestionData() {
        // 1. Treść pytania
        const questionTextEl = document.querySelector(
            '.question_essence, .question_text_content, .question_content, .question-text, .question_text, ' +
            '.test-question-content, [class*="question_text"], [class*="question-text"], .question, h1, h2, h3'
        ) || document.body;

        const questionText = questionTextEl ? questionTextEl.innerText.trim().slice(0, 1000) : '';

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

        // Kontenery odpowiedzi
        let containers = [];
        if (checkboxes.length > 0) {
            containers = checkboxes.map(i => i.closest('.answer_container, label, .question_option_wrapper, .answer_item, tr, li') || i.parentElement || i);
        } else if (radioInputs.length > 0) {
            containers = radioInputs.map(i => i.closest('.answer_container, label, .question_option_wrapper, .answer_item, tr, li') || i.parentElement || i);
        } else if (!openInput) {
            containers = Array.from(document.querySelectorAll(
                '.answer_container, .question_answers .answer, #questionForm .question_option_wrapper, label.answer, .true-false-option, .tf_option'
            ));
        }

        let options = [];
        const resolvedContainers = [];

        containers.forEach((el) => {
            let text = el.innerText ? el.innerText.trim() : el.textContent ? el.textContent.trim() : '';
            const img = el.querySelector('img');
            const imgUrl = img ? img.src : null;

            if (text && text.length < 300) {
                if (!options.some(o => o.text === text)) {
                    options.push({ id: options.length, text: text, imageUrl: imgUrl });
                    resolvedContainers.push(el);
                }
            } else if (img) {
                // Wariant graficzny bez tekstu (np. zadania z sekwencjami figur / obrazkami)
                const label = 'Opcja ' + (options.length + 1);
                options.push({ id: options.length, text: label, imageUrl: imgUrl, isImageOnly: true });
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

        const extracted = {
            type: type,
            question: questionText,
            options: options,
            imageUrl: imgUrl
        };

        return extracted;
    }

    // Pogrubianie pierwszej litery lub prefiksu
    function boldFirstLetterOfElement(targetElement) {
        if (!targetElement) return;
        if (targetElement.getAttribute('data-makarena-marked')) return;

        // 1. Sprawdzamy czy istnieje bezpośredni span z literą opcji (A., B., 1., itp.)
        const letterSpan = targetElement.querySelector('.option_letter, .answer_letter, .letter, [class*="letter"]');
        if (letterSpan) {
            const orig = letterSpan.innerText;
            isProcessing = true;
            letterSpan.innerHTML = '<strong style="font-weight: 900; font-size: 1.25em; color: #000; text-decoration: underline;">' + escapeHTML(orig) + '</strong>';
            isProcessing = false;
            modifiedNodes.push({ element: letterSpan, originalHTML: orig });
            targetElement.setAttribute('data-makarena-marked', '1');
            return;
        }

        // 2. Przeszukujemy węzły tekstowe bezpośrednio w kontenerze odpowiedzi
        const walker = document.createTreeWalker(targetElement, NodeFilter.SHOW_TEXT, null, false);
        let node = walker.nextNode();
        while (node) {
            const text = node.nodeValue;
            if (text && text.trim().length > 0) {
                const trimmed = text.trimStart();
                const leadingWhitespace = text.slice(0, text.length - trimmed.length);
                
                // Prefiks lub pierwsza litera
                const match = trimmed.match(/^([A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż0-9][\)\.\:\-]?)/);
                let boldLength = 1;
                if (match && match[1]) {
                    boldLength = match[1].length;
                }

                const firstPart = trimmed.slice(0, boldLength);
                const restPart = trimmed.slice(boldLength);

                const span = document.createElement('span');
                isProcessing = true;
                span.innerHTML = leadingWhitespace + '<strong style="font-weight: 900; font-size: 1.25em; color: #000; text-decoration: underline;">' + escapeHTML(firstPart) + '</strong>' + escapeHTML(restPart);
                
                if (node.parentNode) {
                    const parentNode = node.parentNode;
                    parentNode.replaceChild(span, node);
                    modifiedNodes.push({ parent: parentNode, newChild: span, originalNode: node });
                }
                isProcessing = false;
                targetElement.setAttribute('data-makarena-marked', '1');
                break;
            }
            node = walker.nextNode();
        }

        // 3. Jeśli brak tekstu (opcja jest czystą grafiką/obrazkiem)
        const optImg = targetElement.querySelector('img') || (targetElement.tagName === 'IMG' ? targetElement : null);
        if (optImg) {
            const origOutline = optImg.style.outline;
            const origShadow = optImg.style.boxShadow;
            isProcessing = true;
            optImg.style.outline = '3px solid #26C6FF';
            optImg.style.boxShadow = '0 0 12px rgba(38, 198, 255, 0.7)';
            optImg.style.borderRadius = '6px';
            isProcessing = false;
            modifiedNodes.push({
                element: optImg,
                revert: function() {
                    optImg.style.outline = origOutline;
                    optImg.style.boxShadow = origShadow;
                }
            });
            targetElement.setAttribute('data-makarena-marked', '1');
            return;
        }
    }

    // Cofnięcie modyfikacji DOM
    function revertModifications() {
        isProcessing = true;
        modifiedNodes.forEach(item => {
            if (item.revert) {
                try { item.revert(); } catch(e) {}
            } else if (item.element && item.originalHTML) {
                item.element.innerHTML = item.originalHTML;
                const parent = item.element.closest('[data-makarena-marked]');
                if (parent) parent.removeAttribute('data-makarena-marked');
            } else if (item.parent && item.newChild && item.originalNode) {
                try {
                    item.parent.replaceChild(item.originalNode, item.newChild);
                    const parent = item.parent.closest('[data-makarena-marked]');
                    if (parent) parent.removeAttribute('data-makarena-marked');
                } catch(e) {}
            }
        });
        modifiedNodes.length = 0;
        document.querySelectorAll('[data-makarena-marked]').forEach(el => el.removeAttribute('data-makarena-marked'));
        isProcessing = false;
    }

    // Aplikowanie podpowiedzi dla pytań zamkniętych i wielokrotnego wyboru
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
            }
        });
    }

    // Aktualizacja podpowiedzi dla pytań otwartych
    function updateGhostForInput(input, ghostOverlay) {
        if (isPanicMode || !input._aiSuggestedAnswer) {
            ghostOverlay.innerHTML = '';
            return;
        }

        const style = window.getComputedStyle(input);
        const rect = input.getBoundingClientRect();

        if (ghostOverlay.offsetWidth === 0 && ghostOverlay.offsetHeight === 0 && rect.width > 0) {
            ghostOverlay.style.position = 'fixed';
            ghostOverlay.style.top = rect.top + 'px';
            ghostOverlay.style.left = rect.left + 'px';
            ghostOverlay.style.width = rect.width + 'px';
            ghostOverlay.style.height = rect.height + 'px';
        } else {
            ghostOverlay.style.position = 'absolute';
            ghostOverlay.style.top = input.offsetTop + 'px';
            ghostOverlay.style.left = input.offsetLeft + 'px';
            ghostOverlay.style.width = input.offsetWidth + 'px';
            ghostOverlay.style.height = input.offsetHeight + 'px';
        }

        ghostOverlay.style.padding = style.padding;
        ghostOverlay.style.fontFamily = style.fontFamily;
        ghostOverlay.style.fontSize = style.fontSize;
        ghostOverlay.style.lineHeight = style.lineHeight;

        const currentVal = input.value || '';
        const suggestion = input._aiSuggestedAnswer;

        if (suggestion.toLowerCase().startsWith(currentVal.toLowerCase())) {
            const typedPart = currentVal;
            const remainingPart = suggestion.slice(typedPart.length);

            ghostOverlay.innerHTML = 
                '<span style="opacity: 0;">' + escapeHTML(typedPart) + '</span>' +
                '<span style="color: #666666; font-weight: 600; background: rgba(0, 85, 255, 0.12); border-radius: 2px;">' + escapeHTML(remainingPart) + '</span>';
        } else {
            ghostOverlay.innerHTML = '<span style="color: #888888; font-style: italic; opacity: 0.65;"> (' + escapeHTML(suggestion) + ')</span>';
        }
    }

    function applyOpenHint(suggestedAnswer) {
        if (isPanicMode || !suggestedAnswer) return;

        const clean = suggestedAnswer.trim();
        const lower = clean.toLowerCase();
        if (lower.includes('404') || lower.includes('not found') || lower.includes('free limit') || lower.includes('error') || lower.includes('too many')) {
            return;
        }

        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]), div[contenteditable="true"]'
        );
        if (!inputs || inputs.length === 0) return;

        inputs.forEach(input => {
            input._aiSuggestedAnswer = clean;

            if (!originalPlaceholders.has(input)) {
                originalPlaceholders.set(input, input.getAttribute('placeholder') || '');
            }
            input.setAttribute('placeholder', clean);

            let ghostOverlay = input._ghostOverlay;
            if (!ghostOverlay) {
                ghostOverlay = document.createElement('div');
                ghostOverlay.className = 'makarena-ghost-overlay';
                ghostOverlay.style.position = 'absolute';
                ghostOverlay.style.pointerEvents = 'none';
                ghostOverlay.style.color = '#777777';
                ghostOverlay.style.whiteSpace = 'pre-wrap';
                ghostOverlay.style.boxSizing = 'border-box';
                ghostOverlay.style.zIndex = '999';

                const parent = input.parentNode;
                if (parent) {
                    if (window.getComputedStyle(parent).position === 'static') {
                        parent.style.position = 'relative';
                    }
                    parent.appendChild(ghostOverlay);
                }
                input._ghostOverlay = ghostOverlay;
                activeGhostOverlays.push({ input: input, overlay: ghostOverlay });
            }

            function updateGhost() {
                updateGhostForInput(input, ghostOverlay);
                if (!input.value) {
                    input.setAttribute('placeholder', clean);
                }
            }

            input.removeEventListener('input', updateGhost);
            input.removeEventListener('keyup', updateGhost);
            input.removeEventListener('focus', updateGhost);
            input.removeEventListener('click', updateGhost);
            input.removeEventListener('touchstart', updateGhost);
            input.removeEventListener('touchend', updateGhost);

            input.addEventListener('input', updateGhost);
            input.addEventListener('keyup', updateGhost);
            input.addEventListener('focus', updateGhost);
            input.addEventListener('click', updateGhost);
            input.addEventListener('touchstart', updateGhost);
            input.addEventListener('touchend', updateGhost);

            updateGhost();
            setTimeout(updateGhost, 80);
        });
    }

    // Panic Mode — natychmiastowe ukrycie wszelkich śladów wizualnych
    function enablePanicMode() {
        isPanicMode = true;
        revertModifications();

        activeGhostOverlays.forEach(item => {
            if (item.overlay) {
                item.overlay.innerHTML = '';
            }
        });

        const inputs = document.querySelectorAll('.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"], div[contenteditable="true"]');
        inputs.forEach(input => {
            if (originalPlaceholders.has(input)) {
                input.setAttribute('placeholder', originalPlaceholders.get(input));
            } else {
                input.setAttribute('placeholder', 'Wprowadź odpowiedź');
            }
        });
        originalPlaceholders.clear();
    }

    function disablePanicMode() {
        isPanicMode = false;
    }

    function checkAndAutoAnalyzeNewQuestion() {
        if (isPanicMode) return;
        if (isProcessing) return;
        const qData = extractQuestionData();
        if (qData && qData.question && qData.question !== currentQuestionText) {
            currentQuestionText = qData.question;
            window.prompt('__makarena_bridge:' + JSON.stringify({
                action: 'questionDataExtracted',
                data: qData
            }), '');
        }
    }

    const mutationObserver = new MutationObserver(function() {
        if (isPanicMode) return;
        if (isProcessing) return;
        checkAndAutoAnalyzeNewQuestion();
    });
    
    if (document.body) {
        mutationObserver.observe(document.body, { childList: true, subtree: true });
    } else {
        document.addEventListener('DOMContentLoaded', function() {
            mutationObserver.observe(document.body, { childList: true, subtree: true });
        });
    }

    // Sprawdzenie przy załadowaniu strony: jeśli asystent był włączony, automatycznie analizujemy nowe pytanie
    if (!isPanicMode) {
        setTimeout(function() {
            if (!isPanicMode) {
                checkAndAutoAnalyzeNewQuestion();
            }
        }, 400);
    }

    setInterval(function() {
        if (isPanicMode) return;
        activeGhostOverlays.forEach(item => {
            if (item.input && item.input._aiSuggestedAnswer) {
                updateGhostForInput(item.input, item.overlay);
            }
        });
    }, 600);

    // Nasłuch zdarzeń sterowania ze Swift
    document.addEventListener('__makarena_action', function(e) {
        if (!e || !e.detail) return;
        const action = e.detail.action;

        if (action === 'analyze') {
            disablePanicMode();
            currentQuestionText = '';
            const data = extractQuestionData();
            if (data) {
                window.prompt('__makarena_bridge:' + JSON.stringify({
                    action: 'questionDataExtracted',
                    data: data
                }), '');
            }
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
})();
