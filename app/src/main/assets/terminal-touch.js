(function() {
  'use strict';
  if (window.__dexcodeTerminalTouchInstalled) return;
  window.__dexcodeTerminalTouchInstalled = true;
  // xterm's inertial gesture changes omit coordinates. With mouse reporting enabled,
  // those missing values become literal NaN fields in terminal input (SGR reports).
  const positions = new WeakMap();
  const screenFor = event => {
    const target = event.target;
    return target && typeof target.closest === 'function' ? target.closest('.xterm-screen') : null;
  };
  function remember(event) {
    const screen = screenFor(event);
    if (!screen) return;
    const touches = event.changedTouches;
    if (!touches || touches.length !== 1 || (event.touches && event.touches.length > 1)) {
      positions.delete(screen); return;
    }
    const touch = touches[0];
    if (Number.isFinite(touch.clientX) && Number.isFinite(touch.clientY)) {
      positions.set(screen, {x:touch.clientX, y:touch.clientY});
    }
  }
  document.addEventListener('touchstart', remember, {capture:true, passive:true});
  document.addEventListener('touchmove', remember, {capture:true, passive:true});
  document.addEventListener('touchcancel', event => {
    const screen=screenFor(event); if(screen)positions.delete(screen);
  }, {capture:true, passive:true});
  document.addEventListener('-xterm-gesturechange', event => {
    const screen=screenFor(event);
    if (!screen) return;
    const stop = () => {event.preventDefault(); event.stopImmediatePropagation();};
    if (!Number.isFinite(event.translationY) || !Number.isFinite(event.translationX)) {stop();return;}
    if (Number.isFinite(event.clientX) && Number.isFinite(event.clientY)) return;
    const last=positions.get(screen), rect=screen.getBoundingClientRect();
    if (!last || !screen.isConnected || !Number.isFinite(rect.left) || !Number.isFinite(rect.top) ||
        !Number.isFinite(rect.width) || !Number.isFinite(rect.height) || rect.width <= 0 || rect.height <= 0) {stop();return;}
    // Keep the anchor inside this screen if Android's keyboard resized the viewport.
    const x=Math.max(rect.left, Math.min(last.x, rect.left+rect.width-0.01));
    const y=Math.max(rect.top, Math.min(last.y, rect.top+rect.height-0.01));
    try {
      Object.defineProperties(event, {
        clientX:{value:x,configurable:true}, clientY:{value:y,configurable:true},
        pageX:{value:x+window.scrollX,configurable:true}, pageY:{value:y+window.scrollY,configurable:true}
      });
    } catch (_) {stop();}
  }, {capture:true, passive:false});
})();
