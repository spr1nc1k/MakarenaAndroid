// testportal_bypass.js
// 100% Native-Spoofed Stealth Engine for Testportal & Safari Environment Parity

(function() {
    'use strict';

    // 1. Function.prototype.toString Spoofing via WeakMap (0 properties, 0 symbols)
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

    // 2. Window.prototype Dimensions Parity with Illegal Invocation enforcement
    try {
        const outerWidthGetter = setNativeToString(function() {
            if (!(this instanceof Window) && this !== window) {
                throw new TypeError("Illegal invocation");
            }
            return window.innerWidth || 390;
        }, 'get outerWidth');

        const outerHeightGetter = setNativeToString(function() {
            if (!(this instanceof Window) && this !== window) {
                throw new TypeError("Illegal invocation");
            }
            return window.innerHeight || 844;
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

    // 3. Document.prototype Focus & Visibility Parity with Illegal Invocation enforcement
    try {
        const visStateGetter = setNativeToString(function() {
            if (!(this instanceof Document) && this !== document) {
                throw new TypeError("Illegal invocation");
            }
            return 'visible';
        }, 'get visibilityState');

        Object.defineProperty(Document.prototype, 'visibilityState', {
            get: visStateGetter,
            enumerable: true,
            configurable: true
        });

        const hiddenGetter = setNativeToString(function() {
            if (!(this instanceof Document) && this !== document) {
                throw new TypeError("Illegal invocation");
            }
            return false;
        }, 'get hidden');

        Object.defineProperty(Document.prototype, 'hidden', {
            get: hiddenGetter,
            enumerable: true,
            configurable: true
        });

        const hasFocusFn = setNativeToString(function hasFocus() {
            if (!(this instanceof Document) && this !== document) {
                throw new TypeError("Illegal invocation");
            }
            return true;
        }, 'hasFocus');

        Object.defineProperty(Document.prototype, 'hasFocus', {
            value: hasFocusFn,
            writable: true,
            enumerable: true,
            configurable: true
        });
    } catch(e) {}

    // 4. Safari Environment Emulation (Matches iOS WebKit profile 1:1)
    if (!('ApplePaySession' in window)) {
        try {
            Object.defineProperty(window, 'ApplePaySession', {
                value: setNativeToString(function ApplePaySession() {}, 'ApplePaySession'),
                writable: false,
                configurable: true,
                enumerable: false
            });
        } catch(e) {}
    }

    try {
        Object.defineProperty(Navigator.prototype, 'standalone', {
            get: setNativeToString(function() { return false; }, 'get standalone'),
            configurable: true,
            enumerable: true
        });
        Object.defineProperty(Navigator.prototype, 'webdriver', {
            get: setNativeToString(function() { return false; }, 'get webdriver'),
            configurable: true,
            enumerable: true
        });
        Object.defineProperty(Navigator.prototype, 'vendor', {
            get: setNativeToString(function() { return 'Apple Computer, Inc.'; }, 'get vendor'),
            configurable: true,
            enumerable: true
        });
        Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
            get: setNativeToString(function() { return 5; }, 'get maxTouchPoints'),
            configurable: true,
            enumerable: true
        });
    } catch(e) {}

    // Strip Chromium-specific footprints that trigger desktop extension checks
    try {
        if ('chrome' in window) {
            delete window.chrome;
        }
    } catch(e) {}

    try {
        if ('userAgentData' in navigator) {
            delete Navigator.prototype.userAgentData;
        }
    } catch(e) {}

    // 5. Native Event Interception with Bidirectional WeakMap (Transparent removeEventListener support)
    const BLOCKED_EVENTS = new Set(['blur', 'focusout', 'visibilitychange', 'pagehide', 'freeze']);
    const originalAddEventListener = EventTarget.prototype.addEventListener;
    const originalRemoveEventListener = EventTarget.prototype.removeEventListener;
    const listenerMap = new WeakMap();

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
            if (listener && typeof listener === 'object' || typeof listener === 'function') {
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
