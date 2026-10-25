// testportal_bypass.js
// 100% Native-Spoofed Stealth Engine for Testportal & Chromium Environment Parity

(function() {
    'use strict';

    if (window.__makarena_bypass_installed) return;
    Object.defineProperty(window, '__makarena_bypass_installed', {
        value: true,
        writable: false,
        enumerable: false,
        configurable: false
    });

    // 1. Function.prototype.toString Spoofing System
    const originalToString = Function.prototype.toString;
    const toStringSymbol = Symbol('__native_str__');

    function setNativeToString(fn, name) {
        if (!fn || (typeof fn !== 'function')) return fn;
        const fnName = name !== undefined ? name : (fn.name || '');
        try {
            Object.defineProperty(fn, toStringSymbol, {
                value: `function ${fnName}() { [native code] }`,
                writable: false,
                enumerable: false,
                configurable: true
            });
        } catch(e) {}
        return fn;
    }

    const customToString = function toString() {
        if (typeof this !== 'function') {
            return originalToString.call(this);
        }
        if (this[toStringSymbol]) {
            return this[toStringSymbol];
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

    // 2. Android Chrome Native Environment Spoofing
    try {
        Object.defineProperty(Navigator.prototype, 'vendor', {
            get: setNativeToString(function() { return 'Google Inc.'; }, 'get vendor'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    try {
        Object.defineProperty(Navigator.prototype, 'webdriver', {
            get: setNativeToString(function() { return false; }, 'get webdriver'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    try {
        Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
            get: setNativeToString(function() { return 5; }, 'get maxTouchPoints'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // Provide window.chrome (present in Google Chrome, missing in standard WebView)
    if (!window.chrome) {
        try {
            const csiFn = setNativeToString(function csi() {}, 'csi');
            const loadTimesFn = setNativeToString(function loadTimes() {}, 'loadTimes');
            window.chrome = {
                app: {
                    isInstalled: false,
                    InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                    RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
                },
                csi: csiFn,
                loadTimes: loadTimesFn
            };
        } catch(e) {}
    }

    // Sanitize navigator.userAgentData to purge 'Android WebView' brand
    try {
        const standardBrands = [
            { brand: 'Chromium', version: '131' },
            { brand: 'Google Chrome', version: '131' },
            { brand: 'Not_A Brand', version: '24' }
        ];

        if (navigator.userAgentData) {
            Object.defineProperty(navigator.userAgentData, 'brands', {
                get: setNativeToString(function() { return standardBrands; }, 'get brands'),
                enumerable: true,
                configurable: true
            });
        } else {
            const uad = {
                brands: standardBrands,
                mobile: true,
                platform: 'Android',
                getHighEntropyValues: setNativeToString(function(hints) {
                    return Promise.resolve({
                        brands: standardBrands,
                        mobile: true,
                        platform: 'Android',
                        architecture: 'arm',
                        bitness: '64',
                        model: 'Pixel 8',
                        platformVersion: '14.0.0',
                        uaFullVersion: '131.0.6778.39'
                    });
                }, 'getHighEntropyValues')
            };
            Object.defineProperty(Navigator.prototype, 'userAgentData', {
                get: setNativeToString(function() { return uad; }, 'get userAgentData'),
                enumerable: true,
                configurable: true
            });
        }
    } catch(e) {}

    // 3. Document.prototype Focus & Visibility Parity (Non-enumerable)
    try {
        const visStateGetter = setNativeToString(function() { return 'visible'; }, 'get visibilityState');
        Object.defineProperty(Document.prototype, 'visibilityState', {
            get: visStateGetter,
            enumerable: false,
            configurable: true
        });

        const hiddenGetter = setNativeToString(function() { return false; }, 'get hidden');
        Object.defineProperty(Document.prototype, 'hidden', {
            get: hiddenGetter,
            enumerable: false,
            configurable: true
        });

        const hasFocusFn = setNativeToString(function hasFocus() { return true; }, 'hasFocus');
        Object.defineProperty(Document.prototype, 'hasFocus', {
            value: hasFocusFn,
            writable: true,
            enumerable: false,
            configurable: true
        });
    } catch(e) {}

    // 4. Window Outer Dimensions Parity
    try {
        Object.defineProperty(window, 'outerWidth', {
            get: setNativeToString(function() { return window.innerWidth || 390; }, 'get outerWidth'),
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(window, 'outerHeight', {
            get: setNativeToString(function() { return window.innerHeight || 844; }, 'get outerHeight'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // 5. Native-Spoofed EventTarget.prototype.addEventListener
    const BLOCKED_EVENTS = new Set(['blur', 'focusout', 'visibilitychange', 'pagehide', 'freeze']);
    const originalAddEventListener = EventTarget.prototype.addEventListener;

    const wrappedAddEventListener = setNativeToString(function addEventListener(type, listener, options) {
        const lowerType = String(type).toLowerCase();
        if (BLOCKED_EVENTS.has(lowerType) && (this === window || this === document || this instanceof Window || this instanceof Document)) {
            const safeListener = function(event) {
                // Suppress trusted OS loss-of-focus events
                if (event && event.isTrusted === true) {
                    try { event.stopImmediatePropagation(); } catch(err) {}
                    try { event.stopPropagation(); } catch(err) {}
                    try { if (event.preventDefault) event.preventDefault(); } catch(err) {}
                    return;
                }
                // Allow synthetic test events from proctoring verification to execute
                if (typeof listener === 'function') {
                    return listener.call(this, event);
                } else if (listener && typeof listener.handleEvent === 'function') {
                    return listener.handleEvent(event);
                }
            };
            setNativeToString(safeListener, (listener && listener.name) || 'listener');
            return originalAddEventListener.call(this, type, safeListener, options);
        }
        return originalAddEventListener.call(this, type, listener, options);
    }, 'addEventListener');

    try {
        Object.defineProperty(EventTarget.prototype, 'addEventListener', {
            value: wrappedAddEventListener,
            writable: true,
            enumerable: false,
            configurable: true
        });
    } catch(e) {}

    // 6. Inline Handler Neutralization (window.onblur, document.onvisibilitychange)
    ['onblur', 'onfocusout', 'onpagehide'].forEach(prop => {
        try {
            Object.defineProperty(window, prop, {
                get: setNativeToString(function() { return null; }, `get ${prop}`),
                set: setNativeToString(function(val) {}, `set ${prop}`),
                enumerable: true,
                configurable: true
            });
        } catch(e) {}
    });

    try {
        Object.defineProperty(document, 'onvisibilitychange', {
            get: setNativeToString(function() { return null; }, 'get onvisibilitychange'),
            set: setNativeToString(function(val) {}, 'set onvisibilitychange'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // 7. Global Capture-Phase Suppressors for OS Loss-of-Focus
    BLOCKED_EVENTS.forEach(eventName => {
        originalAddEventListener.call(window, eventName, setNativeToString(function(e) {
            if (e && e.isTrusted === true) {
                try { e.stopImmediatePropagation(); } catch(err) {}
                try { e.stopPropagation(); } catch(err) {}
                try { if (e.preventDefault) e.preventDefault(); } catch(err) {}
            }
        }, 'suppressor'), true);
    });

})();
