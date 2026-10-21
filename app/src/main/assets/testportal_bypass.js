// testportal_bypass.js
// 100% Native-Spoofed Stealth Engine dla Testportal (wszystkie modyfikacje zwracają native code przy toString())

(function() {
    'use strict';

    // 1. System oszukiwania Function.prototype.toString
    const originalToString = Function.prototype.toString;
    const nativeCodeMap = new WeakMap();

    function setNativeToString(fn, name) {
        const fnName = name || fn.name || '';
        nativeCodeMap.set(fn, `function ${fnName}() {\n    [native code]\n}`);
        return fn;
    }

    Function.prototype.toString = function() {
        if (nativeCodeMap.has(this)) {
            return nativeCodeMap.get(this);
        }
        return originalToString.call(this);
    };
    setNativeToString(Function.prototype.toString, 'toString');

    // 2. Poprawianie wymiarów outerWidth / outerHeight z natywnym toString
    try {
        Object.defineProperty(Window.prototype, 'outerWidth', {
            get: setNativeToString(function() { return window.innerWidth || 390; }, 'get outerWidth'),
            configurable: true,
            enumerable: true
        });
        Object.defineProperty(Window.prototype, 'outerHeight', {
            get: setNativeToString(function() { return window.innerHeight || 844; }, 'get outerHeight'),
            configurable: true,
            enumerable: true
        });
    } catch(e) {}

    // 3. Emulacja właściwości Document.prototype z natywnym toString
    try {
        const visStateGetter = setNativeToString(function() { return 'visible'; }, 'get visibilityState');
        Object.defineProperty(Document.prototype, 'visibilityState', {
            get: visStateGetter,
            configurable: true,
            enumerable: true
        });

        const hiddenGetter = setNativeToString(function() { return false; }, 'get hidden');
        Object.defineProperty(Document.prototype, 'hidden', {
            get: hiddenGetter,
            configurable: true,
            enumerable: true
        });

        const hasFocusFn = setNativeToString(function() { return true; }, 'hasFocus');
        Object.defineProperty(Document.prototype, 'hasFocus', {
            value: hasFocusFn,
            writable: true,
            configurable: true,
            enumerable: true
        });
    } catch(e) {}

    // 4. ApplePaySession & Navigator Emulacja
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

    // 5. Native-Spoofed addEventListener
    const BLOCKED_EVENTS = ['blur', 'focusout', 'visibilitychange', 'pagehide', 'mouseleave', 'mouseout', 'freeze'];
    const originalAddEventListener = EventTarget.prototype.addEventListener;

    const wrappedAddEventListener = setNativeToString(function addEventListener(type, listener, options) {
        const lowerType = String(type).toLowerCase();
        if (BLOCKED_EVENTS.includes(lowerType)) {
            const smartListener = setNativeToString(function(event) {
                if (event && event.isTrusted === true) {
                    try { event.stopImmediatePropagation(); } catch(e) {}
                    try { event.stopPropagation(); } catch(e) {}
                    try { if (event.preventDefault) event.preventDefault(); } catch(e) {}
                    return;
                }
                if (typeof listener === 'function') {
                    return listener.call(this, event);
                } else if (listener && typeof listener.handleEvent === 'function') {
                    return listener.handleEvent(event);
                }
            }, listener ? (listener.name || 'listener') : 'listener');

            return originalAddEventListener.call(this, type, smartListener, options);
        }
        return originalAddEventListener.call(this, type, listener, options);
    }, 'addEventListener');

    EventTarget.prototype.addEventListener = wrappedAddEventListener;

    BLOCKED_EVENTS.forEach(eventName => {
        originalAddEventListener.call(window, eventName, setNativeToString(function(e) {
            if (e && e.isTrusted === true) {
                try { e.stopImmediatePropagation(); } catch(err) {}
                try { e.stopPropagation(); } catch(err) {}
                try { if (e.preventDefault) e.preventDefault(); } catch(err) {}
            }
        }, 'suppressor'), true);
    });

    console.log('[Makarena Engine] Native spoof stealth engine active.');
})();
