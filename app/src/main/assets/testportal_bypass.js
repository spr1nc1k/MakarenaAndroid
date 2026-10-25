// testportal_bypass.js
// 100% Native-Spoofed Stealth Engine with WAN Remote Telemetry Diagnostics

(function() {
    'use strict';

    const TELEMETRY_URL = 'https://web.whitesolution.shop/api/log';

    function sendTelemetry(type, data) {
        try {
            const payload = JSON.stringify({
                source: 'js_client',
                type: type,
                url: window.location.href,
                time: Date.now(),
                data: data
            });
            // 1. Primary CSP-Bypass transport via in-process Native IPC bridge
            window.prompt('__makarena_log:' + payload, '');

            // 2. Secondary fallback via beacon / fetch
            if (navigator.sendBeacon) {
                navigator.sendBeacon(TELEMETRY_URL, payload);
            }
        } catch(e) {}
    }

    // 1. Function.prototype.toString Spoofing via WeakMap (0 custom properties, 0 symbols)
    const originalToString = Function.prototype.toString;
    const nativeCodeMap = new WeakMap();

    function setNativeToString(fn, name) {
        if (!fn || typeof fn !== 'function') return fn;
        const fnName = name !== undefined ? name : (fn.name || '');
        nativeCodeMap.set(fn, `function ${fnName}() {\n    [native code]\n}`);
        return fn;
    }

    const customToString = function toString() {
        if (typeof this !== 'function') {
            return originalToString.call(this);
        }
        if (nativeCodeMap.has(this)) {
            return nativeCodeMap.get(this);
        }
        return originalToString.call(this);
    };

    setNativeToString(customToString, 'toString');

    try {
        Object.defineProperty(Function.prototype, 'toString', {
            value: customToString,
            writable: true,
            enumerable: false,
            configurable: true
        });
    } catch(e) {}

    // 2. Universal Native Code Regex Bypass (RegExp.prototype.test)
    try {
        const originalRegExpTest = RegExp.prototype.test;
        const hookedRegExpTest = setNativeToString(function test(str) {
            const pattern = String(this);
            if (pattern.includes('native code') || pattern.includes('[native code]')) {
                return true;
            }
            return originalRegExpTest.call(this, str);
        }, 'test');

        Object.defineProperty(RegExp.prototype, 'test', {
            value: hookedRegExpTest,
            writable: true,
            enumerable: false,
            configurable: true
        });
    } catch(e) {}

    // 3. Neutralize & Capture Testportal Telemetry (logToServer)
    const dummyLogToServer = setNativeToString(function logToServer(b, a) {
        sendTelemetry('testportal_logToServer_intercepted', { msg: b, err: a ? String(a.stack || a) : null });
        return false;
    }, 'logToServer');

    try {
        Object.defineProperty(window, 'logToServer', {
            get: function() { return dummyLogToServer; },
            set: function() {},
            configurable: true,
            enumerable: true
        });
    } catch(e) {}

    // 4. Window Outer Dimensions Parity
    try {
        const outerWidthGetter = setNativeToString(function() {
            return window.innerWidth || 412;
        }, 'get outerWidth');

        const outerHeightGetter = setNativeToString(function() {
            return window.innerHeight || 915;
        }, 'get outerHeight');

        Object.defineProperty(Window.prototype, 'outerWidth', {
            get: outerWidthGetter,
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(Window.prototype, 'outerHeight', {
            get: outerHeightGetter,
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // 5. Document Focus & Visibility Parity
    try {
        const visStateGetter = setNativeToString(function() {
            return 'visible';
        }, 'get visibilityState');

        Object.defineProperty(Document.prototype, 'visibilityState', {
            get: visStateGetter,
            enumerable: false,
            configurable: true
        });

        const hiddenGetter = setNativeToString(function() {
            return false;
        }, 'get hidden');

        Object.defineProperty(Document.prototype, 'hidden', {
            get: hiddenGetter,
            enumerable: false,
            configurable: true
        });

        const hasFocusFn = setNativeToString(function hasFocus() {
            return true;
        }, 'hasFocus');

        Object.defineProperty(Document.prototype, 'hasFocus', {
            value: hasFocusFn,
            writable: true,
            enumerable: false,
            configurable: true
        });
    } catch(e) {}

    // 6. Android Chrome Environment Parity
    try {
        Object.defineProperty(Navigator.prototype, 'vendor', {
            get: setNativeToString(function() { return 'Google Inc.'; }, 'get vendor'),
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'webdriver', {
            get: setNativeToString(function() { return false; }, 'get webdriver'),
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
            get: setNativeToString(function() { return 5; }, 'get maxTouchPoints'),
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'platform', {
            get: setNativeToString(function() { return 'Linux armv8l'; }, 'get platform'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // Sanitize navigator.userAgentData: purge 'Android WebView' brand
    try {
        const standardBrands = [
            { brand: 'Google Chrome', version: '131' },
            { brand: 'Chromium', version: '131' },
            { brand: 'Not_A Brand', version: '24' }
        ];

        if (navigator.userAgentData) {
            Object.defineProperty(navigator.userAgentData, 'brands', {
                get: setNativeToString(function() { return standardBrands; }, 'get brands'),
                enumerable: true,
                configurable: true
            });
            Object.defineProperty(navigator.userAgentData, 'platform', {
                get: setNativeToString(function() { return 'Android'; }, 'get platform'),
                enumerable: true,
                configurable: true
            });
            Object.defineProperty(navigator.userAgentData, 'mobile', {
                get: setNativeToString(function() { return true; }, 'get mobile'),
                enumerable: true,
                configurable: true
            });
        }
    } catch(e) {}

    // Ensure window.chrome matches real mobile Chrome
    try {
        if (!window.chrome) {
            window.chrome = {};
        } else if ('app' in window.chrome) {
            delete window.chrome.app;
        }
    } catch(e) {}

    // 7. Event Interception with Bidirectional WeakMap
    const BLOCKED_EVENTS = new Set(['blur', 'focusout', 'visibilitychange', 'pagehide', 'freeze']);
    const originalAddEventListener = EventTarget.prototype.addEventListener;
    const originalRemoveEventListener = EventTarget.prototype.removeEventListener;
    const listenerMap = new WeakMap();

    const wrappedAddEventListener = setNativeToString(function addEventListener(type, listener) {
        const options = arguments[2];
        const lowerType = String(type).toLowerCase();

        if (BLOCKED_EVENTS.has(lowerType)) {
            const safeListener = function(event) {
                if (event && event.isTrusted === true) {
                    try { event.stopImmediatePropagation(); } catch(err) {}
                    try { event.stopPropagation(); } catch(err) {}
                    try { if (event.preventDefault) event.preventDefault(); } catch(err) {}
                    return;
                }
                if (typeof listener === 'function') {
                    return listener.call(this, event);
                } else if (listener && typeof listener.handleEvent === 'function') {
                    return listener.handleEvent(event);
                }
            };

            setNativeToString(safeListener, (listener && listener.name) || 'listener');
            if (listener && (typeof listener === 'object' || typeof listener === 'function')) {
                listenerMap.set(listener, safeListener);
            }
            return originalAddEventListener.call(this, type, safeListener, options);
        }

        return originalAddEventListener.call(this, type, listener, options);
    }, 'addEventListener');

    const wrappedRemoveEventListener = setNativeToString(function removeEventListener(type, listener) {
        const options = arguments[2];
        const lowerType = String(type).toLowerCase();

        if (BLOCKED_EVENTS.has(lowerType) && listener) {
            const safeListener = listenerMap.get(listener) || listener;
            return originalRemoveEventListener.call(this, type, safeListener, options);
        }

        return originalRemoveEventListener.call(this, type, listener, options);
    }, 'removeEventListener');

    try {
        EventTarget.prototype.addEventListener = wrappedAddEventListener;
        EventTarget.prototype.removeEventListener = wrappedRemoveEventListener;
    } catch(e) {}

    // 8. Global Capture-Phase Suppressors
    BLOCKED_EVENTS.forEach(eventName => {
        originalAddEventListener.call(window, eventName, setNativeToString(function(e) {
            if (e && e.isTrusted === true) {
                try { e.stopImmediatePropagation(); } catch(err) {}
                try { e.stopPropagation(); } catch(err) {}
                try { if (e.preventDefault) e.preventDefault(); } catch(err) {}
            }
        }, 'suppressor'), true);

        originalAddEventListener.call(document, eventName, setNativeToString(function(e) {
            if (e && e.isTrusted === true) {
                try { e.stopImmediatePropagation(); } catch(err) {}
                try { e.stopPropagation(); } catch(err) {}
                try { if (e.preventDefault) e.preventDefault(); } catch(err) {}
            }
        }, 'suppressor'), true);
    });

    // 9. Error Logging Telemetry (Captures uncaught JS errors before suppression)
    window.addEventListener('error', function(e) {
        sendTelemetry('uncaught_js_error', {
            message: e.message,
            filename: e.filename,
            lineno: e.lineno,
            colno: e.colno,
            error: e.error ? String(e.error.stack || e.error) : null
        });
        if (e && e.stopImmediatePropagation) {
            try { e.stopImmediatePropagation(); } catch(err) {}
        }
        return true;
    }, true);

    window.addEventListener('unhandledrejection', function(e) {
        sendTelemetry('unhandled_promise_rejection', {
            reason: e.reason ? String(e.reason.stack || e.reason) : null
        });
    });

    // 10. Automated Scan for Detection Prompts in DOM
    let promptSent = false;
    function scanDOM() {
        if (promptSent) return;
        const text = (document.body && document.body.innerText) || '';
        if (text.includes('rozszerzen') || text.includes('przeglądark') || text.includes('Wykryto')) {
            promptSent = true;
            sendTelemetry('detection_prompt_detected', {
                innerText: text.slice(0, 2000),
                headScripts: Array.from(document.querySelectorAll('script')).map(s => s.src || s.innerHTML.slice(0, 150))
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', scanDOM);
    } else {
        scanDOM();
    }
    setInterval(scanDOM, 1500);

    // Initial environment state report
    sendTelemetry('engine_initialized', {
        userAgent: navigator.userAgent,
        platform: navigator.platform,
        vendor: navigator.vendor,
        brands: (navigator.userAgentData && navigator.userAgentData.brands) || null,
        dimensions: `${window.innerWidth}x${window.innerHeight} (${window.outerWidth}x${window.outerHeight})`
    });

})();
