// ai_injector.js — Makarena AI Engine v1.2.1
// Platform: Testportal (Vue/Angular SPA, DOM-structure-agnostic extractor)
// Stealth Indicator: ONLY BOLDS THE LETTER (NO frame, NO border, NO dot, NO background)
// End-to-end Telemetry: full audit trail sent to https://web.whitesolution.shop/api/log

(function() {
    'use strict';

    if (window.__makarena_injector_loaded) return;
    window.__makarena_injector_loaded = true;

    if (typeof window.__makarena_ai_active === 'undefined') {
        window.__makarena_ai_active = true;
    }

    // ─── Logging ──────────────────────────────────────────────────────────────
    function sendLog(type, data) {
        try {
            window.prompt('__makarena_log:' + JSON.stringify({
                type: type,
                data: Object.assign({
                    timestamp: Date.now(),
                    url: window.location.href,
                    aiActive: window.__makarena_ai_active
                }, data || {})
            }), '');
        } catch (_) {}
    }

    sendLog('js_injector_initialized', {
        active: window.__makarena_ai_active,
        readyState: document.readyState
    });

    // ─── State ────────────────────────────────────────────────────────────────
    let isPanicMode = false;
    let isProcessing = false;
    let currentQuestionSignature = '';
    let cachedOptionElements = [];
    let lastResolvedIndices = [];
    let lastResolvedOpenAnswer = '';

    const styledOptionElements = [];
    const originalPlaceholders = new Map();
    const originalValues = new Map();
    const originalTinyMceValues = new Map();

    // ─── DOM helpers ─────────────────────────────────────────────────────────

    function escapeHTML(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function sanitizeQuestionText(raw) {
        if (!raw) return '';
        return raw
            .replace(/Pozostały\s+czas[:\s]+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?/gi, '')
            .replace(/Czas\s+na\s+odpowiedź[:\s]+[0-9]{1,2}:[0-9]{2}/gi, '')
            .replace(/\b[0-9]{1,2}:[0-9]{2}\b/g, '')
            .replace(/^\s*(?:Pytanie|Pyt\.?|Zadanie|Zad\.?)\s*\.?\:?\s*[0-9]+\s*\.?\:?\s*/i, '')
            .replace(/\s+/g, ' ')
            .trim();
    }

    function getDeepText(el, maxLen) {
        if (!el) return '';
        const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
        return t.slice(0, maxLen || 2000);
    }

    function safeGetClass(el) {
        if (!el) return '';
        if (typeof el.className === 'string') return el.className;
        if (el.getAttribute) return el.getAttribute('class') || '';
        return '';
    }

    // ─── Question extraction: structure-agnostic ───────────────────────────

    function findQuestionElement() {
        const KNOWN_Q_SELECTORS = [
            '.question_essence',
            '.question_text_content',
            '.question_text',
            '.question-text',
            '.question_content',
            '[class*="question_text"]',
            '[class*="question-text"]',
            '[class*="questionText"]',
            '[class*="question_essence"]',
            '.test-question-content',
            '[class*="questionContent"]',
            '[data-testid*="question"]',
        ];
        for (const sel of KNOWN_Q_SELECTORS) {
            const el = document.querySelector(sel);
            if (el) {
                const t = sanitizeQuestionText(getDeepText(el, 1500));
                if (t.length > 5) return { el, text: t, selector: sel };
            }
        }

        const forms = Array.from(document.querySelectorAll('form, fieldset, [role="radiogroup"], [role="group"]'));
        for (const f of forms) {
            let prev = f.previousElementSibling;
            while (prev) {
                const t = sanitizeQuestionText(getDeepText(prev, 1500));
                if (t.length > 10) return { el: prev, text: t, selector: 'preceding_sibling' };
                prev = prev.previousElementSibling;
            }
            if (f.parentElement) {
                const h = f.parentElement.querySelector('h1, h2, h3, h4, .title, [class*="title"]');
                if (h) {
                    const t = sanitizeQuestionText(getDeepText(h, 1500));
                    if (t.length > 5) return { el: h, text: t, selector: 'form_heading' };
                }
            }
        }

        const candidates = Array.from(document.querySelectorAll('p, h2, h3, [class*="content"], [class*="Content"]'));
        let best = null, bestLen = 0;
        for (const el of candidates) {
            if (el.querySelector('input, select, textarea, button')) continue;
            const t = sanitizeQuestionText(getDeepText(el, 1500));
            if (t.length > 15 && t.length > bestLen) {
                best = { el, text: t, selector: 'longest_text_candidate' };
                bestLen = t.length;
            }
        }
        if (best) return best;

        return null;
    }

    function findOptionContainers() {
        const ANSWER_CLASSES = [
            '.question_answers .question_answer',
            '.question_answer',
            '[class*="question_answer"]',
            '[class*="answerOption"]',
            '[class*="answer_option"]',
            '[class*="answer-option"]',
            '[class*="answer_item"]',
            '[class*="answerItem"]',
            '.answer_container',
            '[class*="answer_container"]',
            '[class*="answerContainer"]',
            '.answer_item',
            'label.answer',
            '.question_option_wrapper',
            '[class*="question_option"]',
            '.true-false-option',
            '.tf_option',
        ];

        for (const sel of ANSWER_CLASSES) {
            const els = Array.from(document.querySelectorAll(sel));
            if (els.length >= 2) {
                const results = extractTextFromContainers(els);
                if (results.length >= 2) return results;
            }
        }

        const CONTROL_SELS = [
            'input[type="radio"]', 'input[type="checkbox"]',
            '[role="radio"]', '[role="checkbox"]',
            '.mdc-radio', '.mdc-checkbox',
            '[class*="radio"]', '[class*="checkbox"]',
        ];

        for (const sel of CONTROL_SELS) {
            const inputs = Array.from(document.querySelectorAll(sel));
            if (inputs.length >= 2) {
                const containers = inputs.map(inp => {
                    let el = inp;
                    for (let i = 0; i < 6; i++) {
                        const p = el.parentElement;
                        if (!p) break;
                        const tag = p.tagName.toLowerCase();
                        if (tag === 'label' || tag === 'li' || tag === 'tr' || tag === 'td') return p;
                        const cls = safeGetClass(p).toLowerCase();
                        if (cls.includes('answer') || cls.includes('option') || cls.includes('choice')) return p;
                        el = p;
                    }
                    return inp.parentElement || inp;
                });
                const results = extractTextFromContainers(containers);
                if (results.length >= 2) return results;
            }
        }

        const labels = Array.from(document.querySelectorAll('label'));
        if (labels.length >= 2) {
            const results = extractTextFromContainers(labels);
            if (results.length >= 2) return results;
        }

        return [];
    }

    function extractTextFromContainers(els) {
        const seen = new Set();
        const results = [];
        for (const el of els) {
            if (!el) continue;
            const textEl = el.querySelector('.answer_text, .option_text, .mdc-form-field, span, div') || el;
            let text = (textEl.innerText || textEl.textContent || '').replace(/\s+/g, ' ').trim();
            text = text.replace(/^[A-Za-z0-9][\.\:\)\-]\s*/, '').trim();
            if (!text || text.length > 400) continue;
            if (seen.has(text)) continue;
            seen.add(text);
            results.push({ el, text });
        }
        return results;
    }

    // ─── Signature ────────────────────────────────────────────────────────────

    function computeQuestionSignature() {
        const qId = document.querySelector('input[name="questionId"], input[name="id"], [data-question-id]');
        const idVal = qId ? (qId.value || qId.getAttribute('data-question-id') || '') : '';

        const qNum = document.querySelector('.question_number, .question_count, [class*="questionNumber"], [class*="question_count"]');
        const numVal = qNum ? (qNum.innerText || '').trim() : '';

        const qInfo = findQuestionElement();
        const qText = qInfo ? qInfo.text.slice(0, 200) : '';

        const sig = idVal + '||' + numVal + '||' + qText;
        return sig.trim();
    }

    // ─── Main extraction ───────────────────────────────────────────────────

    function extractQuestionData() {
        try {
            const qInfo = findQuestionElement();
            const questionText = qInfo ? qInfo.text : '';

            const imgEl = document.querySelector(
                '.question_essence img, .question_content img, [class*="question"] img, #questionForm img, .question_image img'
            );
            const imgUrl = (imgEl && imgEl.src && !imgEl.src.includes('logo') && !imgEl.src.includes('icon_')) ? imgEl.src : null;

            const openInput = document.querySelector(
                '#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"], ' +
                '.rich-text-answer-container, .all_short_answers input, ' +
                'textarea, div[contenteditable="true"]'
            );
            const shortInput = document.querySelector(
                '.mdc-text-field__input, input[type="text"]:not([name*="search"]):not([name*="url"]):not([name*="q"])'
            );

            const optionData = findOptionContainers();
            cachedOptionElements = optionData.map(o => o.el);
            const options = optionData.map((o, i) => ({ id: i, text: o.text }));

            let type = 'unknown';
            const hasCheckbox = !!document.querySelector(
                'input[type="checkbox"], [role="checkbox"], .mdc-checkbox, [class*="checkbox"]'
            );
            const hasRadio = !!document.querySelector(
                'input[type="radio"], [role="radio"], .mdc-radio, [class*="radio__"]'
            );

            if (openInput) {
                type = 'open';
            } else if (shortInput && options.length === 0) {
                type = 'open';
            } else if (hasCheckbox && options.length >= 2) {
                type = 'multiple';
            } else if (options.length >= 2) {
                type = 'closed';
            } else if (hasRadio) {
                type = 'closed';
            } else if (options.length === 1) {
                type = 'closed';
            } else if (shortInput) {
                type = 'open';
            }

            if (type === 'open') cachedOptionElements = [];

            sendLog('dom_extraction_success', {
                detectedType: type,
                questionPreview: questionText.slice(0, 100),
                questionLength: questionText.length,
                selectorUsed: qInfo ? qInfo.selector : 'none',
                optionsCount: options.length,
                optionsList: options.map(o => ({ id: o.id, text: o.text })),
                hasRadio: hasRadio,
                hasCheckbox: hasCheckbox,
                hasOpenInput: !!openInput || !!shortInput
            });

            return {
                type: type,
                question: questionText,
                options: options,
                imageUrl: imgUrl
            };
        } catch (e) {
            sendLog('dom_extraction_error', { error: String(e) });
            return null;
        }
    }

    // ─── Stealth Indicator: ONLY BOLD THE LETTER (No Frame, No Dot) ────────

    function boldLetterOnly(el, idx) {
        if (!el) return;
        try {
            // 1. Check if the element already contains an explicit letter / number badge
            const letterEl = el.querySelector(
                '.answer_letter, .answer_number, [class*="letter"], [class*="number"], [class*="index"], .option_letter, [class*="badge"]'
            );
            if (letterEl && letterEl.innerText && letterEl.innerText.trim().length <= 4) {
                letterEl.style.setProperty('font-weight', '900', 'important');
                letterEl.setAttribute('data-makarena-bold', '1');
                styledOptionElements.push(letterEl);
                sendLog('letter_bolded', { method: 'explicit_element', index: idx, text: letterEl.innerText.trim() });
                return;
            }

            // 2. Find the visible text container
            const textEl = el.querySelector('.answer_text, .option_text, label, .mdc-form-field, span') || el;
            if (!textEl) return;

            // Search for the first text node with text and wrap ONLY the letter/prefix in bold
            const walker = document.createTreeWalker(textEl, NodeFilter.SHOW_TEXT, null, false);
            let node = walker.nextNode();
            while (node) {
                const val = node.nodeValue;
                if (val && val.trim().length > 0) {
                    const match = val.match(/^(\s*)([A-Za-z0-9\(\[\.\:\-]\s*)/);
                    if (match) {
                        const lead = match[1];
                        const charPart = match[2];
                        const tail = val.slice(lead.length + charPart.length);

                        const span = document.createElement('span');
                        span.setAttribute('data-makarena-letter', '1');
                        span.style.setProperty('font-weight', '900', 'important');
                        span.textContent = charPart;

                        const parent = node.parentNode;
                        if (lead.length > 0) parent.insertBefore(document.createTextNode(lead), node);
                        parent.insertBefore(span, node);
                        if (tail.length > 0) parent.insertBefore(document.createTextNode(tail), node);
                        parent.removeChild(node);

                        styledOptionElements.push(span);
                        sendLog('letter_bolded', { method: 'wrapped_char', index: idx, char: charPart.trim() });
                        return;
                    }
                }
                node = walker.nextNode();
            }

            // 3. Fallback: bold the text element
            textEl.style.setProperty('font-weight', '900', 'important');
            textEl.setAttribute('data-makarena-bold', '1');
            styledOptionElements.push(textEl);
            sendLog('letter_bolded', { method: 'container_fallback', index: idx });
        } catch (e) {
            sendLog('bold_letter_error', { index: idx, error: String(e) });
        }
    }

    function revertModifications() {
        styledOptionElements.forEach(el => {
            if (!el) return;
            try {
                if (el.hasAttribute && el.hasAttribute('data-makarena-letter')) {
                    const text = el.textContent || '';
                    const textNode = document.createTextNode(text);
                    if (el.parentNode) el.parentNode.replaceChild(textNode, el);
                } else if (el.style) {
                    el.style.removeProperty('font-weight');
                    el.removeAttribute && el.removeAttribute('data-makarena-bold');
                }
            } catch (_) {}
        });
        styledOptionElements.length = 0;

        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]), div[contenteditable="true"]'
        );
        inputs.forEach(input => {
            try {
                if (originalPlaceholders.has(input)) {
                    input.setAttribute('placeholder', originalPlaceholders.get(input));
                    input.placeholder = originalPlaceholders.get(input);
                }
            } catch (_) {}
        });

        const iframe = document.querySelector('#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"]');
        if (iframe && originalTinyMceValues.has(iframe)) {
            try {
                const ifrDoc = iframe.contentDocument || iframe.contentWindow.document;
                if (ifrDoc && ifrDoc.body) {
                    ifrDoc.body.innerHTML = originalTinyMceValues.get(iframe);
                }
            } catch (_) {}
        }
    }

    // ─── Apply hints ─────────────────────────────────────────────────────────

    function applyClosedHint(indicesArg) {
        if (isPanicMode || !window.__makarena_ai_active) {
            sendLog('applyClosed_ignored_inactive', { panic: isPanicMode, active: window.__makarena_ai_active });
            return;
        }

        revertModifications();

        let indices = [];
        if (Array.isArray(indicesArg)) indices = indicesArg;
        else if (typeof indicesArg === 'number') indices = [indicesArg];

        lastResolvedIndices = indices;
        if (indices.length === 0) {
            sendLog('applyClosed_empty_indices', {});
            return;
        }

        let targets = cachedOptionElements;
        if (!targets || targets.length === 0) {
            const fresh = findOptionContainers();
            cachedOptionElements = fresh.map(o => o.el);
            targets = cachedOptionElements;
        }

        sendLog('applyClosed_executing_bold_letter', {
            targetIndices: indices,
            availableContainersCount: targets.length
        });

        indices.forEach(idx => {
            if (targets && targets[idx]) {
                boldLetterOnly(targets[idx], idx);
            } else {
                sendLog('applyClosed_target_out_of_bounds', {
                    requestedIndex: idx,
                    availableCount: targets ? targets.length : 0
                });
            }
        });
    }

    function applyOpenHint(suggestedAnswer) {
        if (isPanicMode || !window.__makarena_ai_active) return;
        if (!suggestedAnswer) return;
        const clean = String(suggestedAnswer).trim();
        if (!clean || clean.length === 0) return;
        const lower = clean.toLowerCase();
        if (lower === 'poprawnie' || lower === 'poprawna' || lower === 'prawda' || lower === 'fałsz' || lower === 'brak') return;

        lastResolvedOpenAnswer = clean;

        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]):not([name*="url"])'
        );
        let modifiedCount = 0;
        inputs.forEach(input => {
            try {
                if (!originalPlaceholders.has(input)) {
                    originalPlaceholders.set(input, input.getAttribute('placeholder') || '');
                }
                input.placeholder = '✦ ' + clean;
                input.setAttribute('placeholder', '✦ ' + clean);
                modifiedCount++;
            } catch (_) {}
        });

        const iframe = document.querySelector('#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"]');
        if (iframe) {
            try {
                const ifrDoc = iframe.contentDocument || iframe.contentWindow.document;
                if (ifrDoc && ifrDoc.body) {
                    if (!originalTinyMceValues.has(iframe)) {
                        originalTinyMceValues.set(iframe, ifrDoc.body.innerHTML || '');
                    }
                    ifrDoc.body.innerHTML = '<p style="color:#555555;font-weight:bold;font-style:italic;">' + escapeHTML(clean) + '</p>';
                    iframe._makarena_inserted = clean;
                    modifiedCount++;
                }
            } catch (_) {}
        }

        sendLog('applyOpen_executed', {
            answer: clean,
            inputsModified: modifiedCount
        });
    }

    // ─── Panic / enable ──────────────────────────────────────────────────────

    function enablePanicMode() {
        isPanicMode = true;
        window.__makarena_ai_active = false;
        lastResolvedIndices = [];
        lastResolvedOpenAnswer = '';
        revertModifications();
        sendLog('panic_mode_enabled', {});
    }

    function disablePanicMode() {
        isPanicMode = false;
        window.__makarena_ai_active = true;
        sendLog('panic_mode_disabled', {});
    }

    // ─── Question change detector ─────────────────────────────────────────

    function checkAndAutoAnalyzeNewQuestion() {
        if (isPanicMode || !window.__makarena_ai_active || isProcessing) return;

        const newSig = computeQuestionSignature();
        if (!newSig || newSig === '||||' || newSig === currentQuestionSignature) return;

        isProcessing = true;
        try {
            currentQuestionSignature = newSig;
            lastResolvedIndices = [];
            lastResolvedOpenAnswer = '';
            revertModifications();

            sendLog('new_question_detected', { signature: newSig });

            const qData = extractQuestionData();
            if (qData && qData.question && qData.question.length > 5) {
                sendLog('bridge_sending_question', {
                    type: qData.type,
                    optionsCount: qData.options ? qData.options.length : 0
                });
                window.prompt('__makarena_bridge:' + JSON.stringify({
                    action: 'questionDataExtracted',
                    data: qData
                }), '');
            } else {
                sendLog('question_extraction_insufficient', {
                    length: qData ? (qData.question ? qData.question.length : 0) : 0
                });
            }
        } finally {
            isProcessing = false;
        }
    }

    // ─── Guardian Tick ───────────────────────────────────────────────────────

    function guardianTick() {
        if (isPanicMode || !window.__makarena_ai_active) return;

        if (lastResolvedIndices && lastResolvedIndices.length > 0) {
            let needsReapply = false;
            for (const idx of lastResolvedIndices) {
                const el = cachedOptionElements[idx];
                if (el && !el.querySelector('[data-makarena-letter]') && !el.hasAttribute('data-makarena-bold')) {
                    needsReapply = true;
                    break;
                }
            }
            if (needsReapply) {
                sendLog('guardian_reapplying_bold', { indices: lastResolvedIndices });
                applyClosedHint(lastResolvedIndices);
            }
        }

        if (lastResolvedOpenAnswer) {
            const inputs = document.querySelectorAll(
                '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"])'
            );
            let needsReapplyOpen = false;
            for (const input of inputs) {
                if (!input.getAttribute('placeholder') || !input.getAttribute('placeholder').includes(lastResolvedOpenAnswer.slice(0, 10))) {
                    needsReapplyOpen = true;
                    break;
                }
            }
            if (needsReapplyOpen) {
                sendLog('guardian_reapplying_open', { answer: lastResolvedOpenAnswer });
                applyOpenHint(lastResolvedOpenAnswer);
            }
        }
    }

    // ─── DOM observation ─────────────────────────────────────────────────────

    let mutationPending = false;
    const mutationObserver = new MutationObserver(function() {
        if (isPanicMode || !window.__makarena_ai_active) return;
        if (mutationPending) return;
        mutationPending = true;
        setTimeout(function() {
            mutationPending = false;
            checkAndAutoAnalyzeNewQuestion();
            guardianTick();
        }, 200);
    });

    function startObserving() {
        if (document.body) {
            mutationObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
        } else {
            document.addEventListener('DOMContentLoaded', function() {
                if (document.body) {
                    mutationObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
                }
            });
        }
    }
    startObserving();

    setInterval(function() {
        if (isPanicMode || !window.__makarena_ai_active) return;
        checkAndAutoAnalyzeNewQuestion();
        guardianTick();
    }, 600);

    // ─── Native bridge event listener ────────────────────────────────────────

    document.addEventListener('__makarena_action', function(e) {
        if (!e || !e.detail) return;
        const action = e.detail.action;
        sendLog('action_event_received', { action: action, detail: e.detail });

        if (action === 'enable' || action === 'analyze') {
            disablePanicMode();
            currentQuestionSignature = '';
            lastResolvedIndices = [];
            lastResolvedOpenAnswer = '';
            revertModifications();
            setTimeout(checkAndAutoAnalyzeNewQuestion, 300);
        } else if (action === 'disable' || action === 'panic') {
            enablePanicMode();
        } else if (action === 'applyClosed') {
            if (isPanicMode || !window.__makarena_ai_active) return;
            const raw = e.detail.correctIndices;
            if (!Array.isArray(raw) || raw.length === 0) return;
            applyClosedHint(raw);
        } else if (action === 'applyOpen') {
            if (isPanicMode || !window.__makarena_ai_active) return;
            applyOpenHint(e.detail.answerText);
        }
    });

    // Initial trigger
    setTimeout(function() {
        if (window.__makarena_ai_active && !isPanicMode) {
            checkAndAutoAnalyzeNewQuestion();
        }
    }, 600);

})();
