// ai_injector.js — Makarena AI Engine v1.2.0
// Platform: Testportal (Vue/Angular SPA, DOM-structure-agnostic extractor)
// Toggle: 2× VOL_DOWN = ON/OFF persistent | 2× VOL_UP = PANIC (clear all)
// Bridge: window.prompt('__makarena_bridge:...') → onJsPrompt → AIService → CustomEvent '__makarena_action'

(function() {
    'use strict';

    if (window.__makarena_injector_loaded) return;
    window.__makarena_injector_loaded = true;

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

    // Deep text extraction: skip elements that are purely answer option containers
    function getDeepText(el, maxLen) {
        if (!el) return '';
        const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
        return t.slice(0, maxLen || 2000);
    }

    // ─── Question extraction: structure-agnostic ───────────────────────────

    // Returns {questionEl, questionText} by broad multi-strategy search
    function findQuestionElement() {
        // Strategy 1: well-known class selectors (Testportal 4.x / 5.x)
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
                if (t.length > 5) return { el, text: t };
            }
        }

        // Strategy 2: any element that immediately precedes a form/fieldset with radios or checkboxes
        const forms = Array.from(document.querySelectorAll('form, fieldset, [class*="answer"], [class*="Answer"]'));
        for (const form of forms) {
            const hasInputs = form.querySelector('input[type="radio"], input[type="checkbox"], [role="radio"], [role="checkbox"]');
            if (hasInputs) {
                // walk backwards in DOM siblings / parent siblings
                let prev = form.previousElementSibling;
                while (prev) {
                    const t = sanitizeQuestionText(getDeepText(prev, 1500));
                    if (t.length > 10 && !prev.querySelector('input')) return { el: prev, text: t };
                    prev = prev.previousElementSibling;
                }
                // try parent's previous sibling
                if (form.parentElement) {
                    let pp = form.parentElement.previousElementSibling;
                    while (pp) {
                        const t = sanitizeQuestionText(getDeepText(pp, 1500));
                        if (t.length > 10) return { el: pp, text: t };
                        pp = pp.previousElementSibling;
                    }
                }
            }
        }

        // Strategy 3: largest text block that is NOT inside a form with inputs
        // Walk all <p>, <div>, <span>, <h2>, <h3> and pick longest meaningful text
        const candidates = Array.from(document.querySelectorAll('p, h2, h3, [class*="content"], [class*="Content"]'));
        let best = null, bestLen = 0;
        for (const el of candidates) {
            if (el.querySelector('input, select, textarea, button')) continue;
            const t = sanitizeQuestionText(getDeepText(el, 1500));
            if (t.length > 15 && t.length > bestLen) {
                best = { el, text: t };
                bestLen = t.length;
            }
        }
        if (best) return best;

        return null;
    }

    // Finds all answer option containers with their text and DOM element
    // Returns Array<{el, text}>
    function findOptionContainers() {
        // Priority: explicit answer class selectors
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

        // Fallback: find all radio / checkbox inputs and walk up to their label/container
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
                    // Walk up to find a meaningful container (label > li > tr > div)
                    let el = inp;
                    for (let i = 0; i < 6; i++) {
                        const p = el.parentElement;
                        if (!p) break;
                        const tag = p.tagName.toLowerCase();
                        if (tag === 'label' || tag === 'li' || tag === 'tr' || tag === 'td') return p;
                        const cls = (p.className || '').toLowerCase();
                        if (cls.includes('answer') || cls.includes('option') || cls.includes('choice')) return p;
                        el = p;
                    }
                    return inp.parentElement || inp;
                });
                const results = extractTextFromContainers(containers);
                if (results.length >= 2) return results;
            }
        }

        // Fallback: <label> elements containing text that are siblings of each other
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
            // Get text from child span/div/label or the element itself
            const textEl = el.querySelector('.answer_text, .option_text, .mdc-form-field, span, div') || el;
            let text = (textEl.innerText || textEl.textContent || '').replace(/\s+/g, ' ').trim();
            // Remove leading letter prefix like "A. " "1) " "a) "
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
        // Use any available unique identifier first
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

            // Image associated with question
            const imgEl = document.querySelector(
                '.question_essence img, .question_content img, [class*="question"] img, #questionForm img, .question_image img'
            );
            const imgUrl = (imgEl && imgEl.src && !imgEl.src.includes('logo') && !imgEl.src.includes('icon_')) ? imgEl.src : null;

            // Open answer input detection (before closed to avoid false positive)
            const openInput = document.querySelector(
                '#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"], ' +
                '.rich-text-answer-container, .all_short_answers input, ' +
                'textarea, div[contenteditable="true"]'
            );
            // Short text input — only treat as open if no radio/checkbox siblings nearby
            const shortInput = document.querySelector(
                '.mdc-text-field__input, input[type="text"]:not([name*="search"]):not([name*="url"]):not([name*="q"])'
            );

            const optionData = findOptionContainers();
            cachedOptionElements = optionData.map(o => o.el);
            const options = optionData.map((o, i) => ({ id: i, text: o.text }));

            // Determine question type
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

            // Log extraction result for telemetry (non-blocking)
            try {
                window.prompt('__makarena_log:' + JSON.stringify({
                    type: 'dom_extraction',
                    data: {
                        questionLen: questionText.length,
                        questionPreview: questionText.slice(0, 80),
                        optionCount: options.length,
                        detectedType: type,
                        hasRadio: hasRadio,
                        hasCheckbox: hasCheckbox,
                        hasOpenInput: !!openInput || !!shortInput,
                    }
                }), '');
            } catch(e) {}

            return {
                type: type,
                question: questionText,
                options: options,
                imageUrl: imgUrl
            };
        } catch (e) {
            try { window.prompt('__makarena_log:' + JSON.stringify({ type: 'extraction_error', data: { msg: String(e) } }), ''); } catch(ee) {}
            return null;
        }
    }

    // ─── Highlight (persistent, toggle-safe) ──────────────────────────────

    function highlightOptionElement(el) {
        if (!el) return;
        try {
            el.style.setProperty('outline', '3px solid #ffffff', 'important');
            el.style.setProperty('background', 'rgba(255,255,255,0.10)', 'important');
            el.style.setProperty('border-radius', '6px', 'important');
            el.setAttribute('data-makarena-active', '1');

            const textEl = el.querySelector('.answer_text, .option_text, label, .mdc-form-field, span') || el;
            if (textEl) {
                textEl.style.setProperty('font-weight', '800', 'important');
                textEl.style.setProperty('color', '#ffffff', 'important');
            }

            const img = el.querySelector('img') || (el.tagName === 'IMG' ? el : null);
            if (img) {
                img.style.setProperty('outline', '3px solid #ffffff', 'important');
                img.style.setProperty('border-radius', '6px', 'important');
            }

            if (!styledOptionElements.includes(el)) styledOptionElements.push(el);
        } catch(e) {}
    }

    function revertModifications() {
        styledOptionElements.forEach(el => {
            if (!el) return;
            try {
                el.style.removeProperty('outline');
                el.style.removeProperty('background');
                el.style.removeProperty('border-radius');
                el.removeAttribute('data-makarena-active');

                const textEl = el.querySelector('.answer_text, .option_text, label, .mdc-form-field, span') || el;
                if (textEl) {
                    textEl.style.removeProperty('font-weight');
                    textEl.style.removeProperty('color');
                }
                const img = el.querySelector('img') || (el.tagName === 'IMG' ? el : null);
                if (img) {
                    img.style.removeProperty('outline');
                    img.style.removeProperty('border-radius');
                }
            } catch(e) {}
        });
        styledOptionElements.length = 0;

        // Revert open inputs
        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]), div[contenteditable="true"]'
        );
        inputs.forEach(input => {
            try {
                if (input._makarena_inserted && input.value === input._makarena_inserted) {
                    input.value = originalValues.get(input) || '';
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }
                input._makarena_inserted = null;
                if (originalPlaceholders.has(input)) {
                    input.setAttribute('placeholder', originalPlaceholders.get(input));
                } else {
                    input.removeAttribute('placeholder');
                }
            } catch(e) {}
        });
        originalValues.clear();
        originalPlaceholders.clear();

        const iframe = document.querySelector('#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"]');
        if (iframe) {
            try {
                const ifrDoc = iframe.contentDocument || iframe.contentWindow.document;
                if (ifrDoc && ifrDoc.body) {
                    if (iframe._makarena_inserted && ifrDoc.body.innerText.trim() === iframe._makarena_inserted) {
                        ifrDoc.body.innerHTML = originalTinyMceValues.get(iframe) || '';
                    }
                    iframe._makarena_inserted = null;
                }
            } catch(e) {}
        }
        originalTinyMceValues.clear();
    }

    // ─── Apply hints ─────────────────────────────────────────────────────────

    function applyClosedHint(indicesArg) {
        if (isPanicMode || !window.__makarena_ai_active) return;

        revertModifications();

        let indices = [];
        if (Array.isArray(indicesArg)) indices = indicesArg;
        else if (typeof indicesArg === 'number') indices = [indicesArg];

        lastResolvedIndices = indices;
        if (indices.length === 0) return;

        // Re-scan if cache stale
        let targets = cachedOptionElements;
        if (!targets || targets.length === 0) {
            const fresh = findOptionContainers();
            cachedOptionElements = fresh.map(o => o.el);
            targets = cachedOptionElements;
        }

        indices.forEach(idx => {
            if (targets && targets[idx]) highlightOptionElement(targets[idx]);
        });
    }

    // Open questions: show answer as placeholder text ONLY (no auto-fill)
    // The hint disappears when AI is toggled OFF (revertModifications handles that)
    function applyOpenHint(suggestedAnswer) {
        if (isPanicMode || !window.__makarena_ai_active) return;
        if (!suggestedAnswer) return;
        const clean = String(suggestedAnswer).trim();
        if (!clean || clean.length === 0) return;
        const lower = clean.toLowerCase();
        if (lower === 'poprawnie' || lower === 'poprawna' || lower === 'prawda' || lower === 'fałsz' || lower === 'brak') return;

        lastResolvedOpenAnswer = clean;

        // Placeholder-only approach: never overwrite field value
        const inputs = document.querySelectorAll(
            '.mdc-text-field__input, .all_short_answers input, textarea, input[type="text"]:not([name*="search"]):not([name*="url"])'
        );
        inputs.forEach(input => {
            try {
                if (!originalPlaceholders.has(input)) {
                    originalPlaceholders.set(input, input.getAttribute('placeholder') || '');
                }
                input.placeholder = '✦ ' + clean;
                input.setAttribute('placeholder', '✦ ' + clean);
            } catch(e) {}
        });

        // TinyMCE: inject inline hint element (overlay text, not actual value)
        const iframe = document.querySelector('#givenAnswer_ifr, iframe[id*="givenAnswer"], iframe[id*="answer"]');
        if (iframe) {
            try {
                const ifrDoc = iframe.contentDocument || iframe.contentWindow.document;
                if (ifrDoc && ifrDoc.body) {
                    if (!originalTinyMceValues.has(iframe)) {
                        originalTinyMceValues.set(iframe, ifrDoc.body.innerHTML || '');
                    }
                    const curText = ifrDoc.body.innerText.trim();
                    if (!curText || curText === iframe._makarena_inserted) {
                        ifrDoc.body.innerHTML = '<p style="color:#aaaaaa;font-style:italic;">' + escapeHTML(clean) + '</p>';
                        iframe._makarena_inserted = clean;
                    }
                }
            } catch(e) {}
        }
    }

    // ─── Panic / enable ──────────────────────────────────────────────────────

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

    // ─── Question change detector ─────────────────────────────────────────

    function checkAndAutoAnalyzeNewQuestion() {
        if (isPanicMode || !window.__makarena_ai_active || isProcessing) return;

        const newSig = computeQuestionSignature();
        // If signature is empty or same as before, do nothing
        // Empty sig means page hasn't rendered question yet — that's fine, MutationObserver will retry
        if (!newSig || newSig === '||||' || newSig === currentQuestionSignature) return;

        isProcessing = true;
        try {
            currentQuestionSignature = newSig;
            lastResolvedIndices = [];
            lastResolvedOpenAnswer = '';
            revertModifications();

            const qData = extractQuestionData();
            if (qData && qData.question && qData.question.length > 5) {
                window.prompt('__makarena_bridge:' + JSON.stringify({
                    action: 'questionDataExtracted',
                    data: qData
                }), '');
            }
        } finally {
            isProcessing = false;
        }
    }

    // ─── Style guardian (re-apply if DOM re-renders) ────────────────────────
    // CRITICAL: guardian only re-applies highlight if indices are still valid
    // and the element lost its marker. It does NOT apply [0] as fallback.

    function guardianTick() {
        if (isPanicMode || !window.__makarena_ai_active) return;

        if (lastResolvedIndices && lastResolvedIndices.length > 0) {
            let needsReapply = false;
            for (const idx of lastResolvedIndices) {
                const el = cachedOptionElements[idx];
                if (el && !el.hasAttribute('data-makarena-active')) {
                    needsReapply = true;
                    break;
                }
            }
            if (needsReapply) applyClosedHint(lastResolvedIndices);
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
            if (needsReapplyOpen) applyOpenHint(lastResolvedOpenAnswer);
        }
    }

    // ─── DOM observation ─────────────────────────────────────────────────────

    let mutationPending = false;
    const mutationObserver = new MutationObserver(function() {
        if (isPanicMode || !window.__makarena_ai_active) return;
        if (mutationPending) return;
        mutationPending = true;
        // Debounce: process mutations after 200ms silence
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

    // Periodic guardian — 500ms interval
    setInterval(function() {
        if (isPanicMode || !window.__makarena_ai_active) return;
        checkAndAutoAnalyzeNewQuestion();
        guardianTick();
    }, 500);

    // ─── Native bridge event listener ────────────────────────────────────────

    document.addEventListener('__makarena_action', function(e) {
        if (!e || !e.detail) return;
        const action = e.detail.action;

        if (action === 'enable' || action === 'analyze') {
            disablePanicMode();
            // Reset signature to force re-analysis of current question
            currentQuestionSignature = '';
            lastResolvedIndices = [];
            lastResolvedOpenAnswer = '';
            revertModifications();
            // Delay to let Vue/Angular finish any pending renders
            setTimeout(checkAndAutoAnalyzeNewQuestion, 300);
        } else if (action === 'disable' || action === 'panic') {
            enablePanicMode();
        } else if (action === 'applyClosed') {
            if (isPanicMode || !window.__makarena_ai_active) return;
            const raw = e.detail.correctIndices;
            // SAFETY: only apply if we actually got a non-empty array
            if (!Array.isArray(raw) || raw.length === 0) return;
            applyClosedHint(raw);
        } else if (action === 'applyOpen') {
            if (isPanicMode || !window.__makarena_ai_active) return;
            applyOpenHint(e.detail.answerText);
        }
    });

    // Initial trigger on page load
    setTimeout(function() {
        if (window.__makarena_ai_active && !isPanicMode) {
            checkAndAutoAnalyzeNewQuestion();
        }
    }, 600);

})();
