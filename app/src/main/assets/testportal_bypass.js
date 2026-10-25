// testportal_bypass.js
// 100% Native-Spoofed Stealth Engine for Testportal & Chromium Environment Parity

(function() {
    'use strict';

    // 1. Function.prototype.toString Spoofing via WeakMap (0 properties, 0 symbols attached to functions)
    const originalToString = Function.prototype.toString;
    const nativeCodeMap = new WeakMap();

    function setNativeToString(fn, name) {
        if (!fn || typeof fn !== 'function') return fn;
        const fnName = name !== undefined ? name : (fn.name || '');
        nativeCodeMap.set(fn, `function ${fnName}() { [native code] }`);
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

    // 2. Window.prototype Outer Dimensions Parity (Defined on Window.prototype, NOT window instance)
    try {
        Object.defineProperty(Window.prototype, 'outerWidth', {
            get: setNativeToString(function() { return window.innerWidth || 390; }, 'get outerWidth'),
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(Window.prototype, 'outerHeight', {
            get: setNativeToString(function() { return window.innerHeight || 844; }, 'get outerHeight'),
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // 3. Document.prototype Focus & Visibility Parity (Standard Enumerable IDL Attributes)
    try {
        const visStateGetter = setNativeToString(function() { return 'visible'; }, 'get visibilityState');
        Object.defineProperty(Document.prototype, 'visibilityState', {
            get: visStateGetter,
            enumerable: true,
            configurable: true
        });

        const hiddenGetter = setNativeToString(function() { return false; }, 'get hidden');
        Object.defineProperty(Document.prototype, 'hidden', {
            get: hiddenGetter,
            enumerable: true,
            configurable: true
        });

        const hasFocusFn = setNativeToString(function hasFocus() { return true; }, 'hasFocus');
        Object.defineProperty(Document.prototype, 'hasFocus', {
            value: hasFocusFn,
            writable: true,
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // 4. Android Chrome Navigator Environment Parity
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

    // Authentic Chrome Mobile window.chrome (csi and loadTimes, NO desktop chrome.app)
    if (!window.chrome) {
        try {
            const csiFn = setNativeToString(function csi() {}, 'csi');
            const loadTimesFn = setNativeToString(function loadTimes() {}, 'loadTimes');
            window.chrome = {
                csi: csiFn,
                loadTimes: loadTimesFn
            };
        } catch(e) {}
    }

    // NavigatorUAData Parity on Prototype
    try {
        const standardBrands = [
            { brand: 'Chromium', version: '131' },
            { brand: 'Google Chrome', version: '131' },
            { brand: 'Not_A Brand', version: '24' }
        ];

        const targetProto = (typeof window.NavigatorUAData !== 'undefined') ? window.NavigatorUAData.prototype : null;

        if (targetProto) {
            Object.defineProperty(targetProto, 'brands', {
                get: setNativeToString(function get brands() { return standardBrands; }, 'get brands'),
                enumerable: true,
                configurable: true
            });

            targetProto.getHighEntropyValues = setNativeToString(function getHighEntropyValues(hints) {
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
            }, 'getHighEntropyValues');
        } else if (navigator.userAgentData) {
            Object.defineProperty(navigator.userAgentData, 'brands', {
                get: setNativeToString(function get brands() { return standardBrands; }, 'get brands'),
                enumerable: true,
                configurable: true
            });
        }
    } catch(e) {}

    // 5. Native-Spoofed EventTarget.prototype.addEventListener
    // Function.length === 2 exactly according to WebIDL specification (type, listener)
    const BLOCKED_EVENTS = new Set(['blur', 'focusout', 'visibilitychange', 'pagehide', 'freeze']);
    const originalAddEventListener = EventTarget.prototype.addEventListener;

    const wrappedAddEventListener = setNativeToString(function addEventListener(type, listener) {
        const options = arguments[2];
        const lowerType = String(type).toLowerCase();
        if (BLOCKED_EVENTS.has(lowerType)) {
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
        EventTarget.prototype.addEventListener = wrappedAddEventListener;
    } catch(e) {}

    // 6. Global Capture-Phase Suppressors for OS Loss-of-Focus
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
