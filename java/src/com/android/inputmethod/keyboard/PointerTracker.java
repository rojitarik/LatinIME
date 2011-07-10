/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.keyboard;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import com.android.inputmethod.keyboard.internal.PointerTrackerQueue;
import com.android.inputmethod.latin.LatinImeLogger;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.SubtypeSwitcher;

import java.util.Arrays;
import java.util.List;

public class PointerTracker {
    private static final String TAG = PointerTracker.class.getSimpleName();
    private static final boolean DEBUG_EVENT = false;
    private static final boolean DEBUG_MOVE_EVENT = false;
    private static final boolean DEBUG_LISTENER = false;
    private static boolean DEBUG_MODE = LatinImeLogger.sDBG;

    public interface DrawingProxy {
        public void invalidateKey(Key key);
        public void showKeyPreview(int keyIndex, PointerTracker tracker);
        public void cancelShowKeyPreview(PointerTracker tracker);
        public void dismissKeyPreview(PointerTracker tracker);
    }

    public interface TimerProxy {
        public void startKeyRepeatTimer(long delay, int keyIndex, PointerTracker tracker);
        public void startLongPressTimer(long delay, int keyIndex, PointerTracker tracker);
        public void startLongPressShiftTimer(long delay, int keyIndex, PointerTracker tracker);
        public void cancelLongPressTimers();
        public void cancelKeyTimers();
    }

    public final int mPointerId;

    // Timing constants
    private final int mDelayBeforeKeyRepeatStart;
    private final int mLongPressKeyTimeout;
    private final int mLongPressShiftKeyTimeout;

    private final DrawingProxy mDrawingProxy;
    private final TimerProxy mTimerProxy;
    private final PointerTrackerQueue mPointerTrackerQueue;
    private KeyDetector mKeyDetector;
    private KeyboardActionListener mListener = EMPTY_LISTENER;
    private final KeyboardSwitcher mKeyboardSwitcher;
    private final boolean mConfigSlidingKeyInputEnabled;

    private final int mTouchNoiseThresholdMillis;
    private final int mTouchNoiseThresholdDistanceSquared;

    private Keyboard mKeyboard;
    private List<Key> mKeys;
    private int mKeyQuarterWidthSquared;

    // The position and time at which first down event occurred.
    private long mDownTime;
    private long mUpTime;

    // The current key index where this pointer is.
    private int mKeyIndex = KeyDetector.NOT_A_KEY;
    // The position where mKeyIndex was recognized for the first time.
    private int mKeyX;
    private int mKeyY;

    // Last pointer position.
    private int mLastX;
    private int mLastY;

    // true if keyboard layout has been changed.
    private boolean mKeyboardLayoutHasBeenChanged;

    // true if event is already translated to a key action (long press or mini-keyboard)
    private boolean mKeyAlreadyProcessed;

    // true if this pointer is repeatable key
    private boolean mIsRepeatableKey;

    // true if this pointer is in sliding key input
    private boolean mIsInSlidingKeyInput;

    // true if sliding key is allowed.
    private boolean mIsAllowedSlidingKeyInput;

    // ignore modifier key if true
    private boolean mIgnoreModifierKey;

    // TODO: Remove these hacking variables
    // true if this pointer is in sliding language switch
    private boolean mIsInSlidingLanguageSwitch;
    private int mSpaceKeyIndex;
    private final SubtypeSwitcher mSubtypeSwitcher;

    // Empty {@link KeyboardActionListener}
    private static final KeyboardActionListener EMPTY_LISTENER = new KeyboardActionListener() {
        @Override
        public void onPress(int primaryCode, boolean withSliding) {}
        @Override
        public void onRelease(int primaryCode, boolean withSliding) {}
        @Override
        public void onCodeInput(int primaryCode, int[] keyCodes, int x, int y) {}
        @Override
        public void onTextInput(CharSequence text) {}
        @Override
        public void onCancelInput() {}
    };

    public PointerTracker(int id, Context context, TimerProxy timerProxy, KeyDetector keyDetector,
            DrawingProxy drawingProxy, PointerTrackerQueue queue) {
        if (drawingProxy == null || timerProxy == null || keyDetector == null)
            throw new NullPointerException();
        mPointerId = id;
        mDrawingProxy = drawingProxy;
        mTimerProxy = timerProxy;
        mPointerTrackerQueue = queue;  // This is null for non-distinct multi-touch device.
        setKeyDetectorInner(keyDetector);
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        final Resources res = context.getResources();
        mConfigSlidingKeyInputEnabled = res.getBoolean(R.bool.config_sliding_key_input_enabled);
        mDelayBeforeKeyRepeatStart = res.getInteger(R.integer.config_delay_before_key_repeat_start);
        mLongPressKeyTimeout = res.getInteger(R.integer.config_long_press_key_timeout);
        mLongPressShiftKeyTimeout = res.getInteger(R.integer.config_long_press_shift_key_timeout);
        mTouchNoiseThresholdMillis = res.getInteger(R.integer.config_touch_noise_threshold_millis);
        final float touchNoiseThresholdDistance = res.getDimension(
                R.dimen.config_touch_noise_threshold_distance);
        mTouchNoiseThresholdDistanceSquared = (int)(
                touchNoiseThresholdDistance * touchNoiseThresholdDistance);
        mSubtypeSwitcher = SubtypeSwitcher.getInstance();
    }

    public void setKeyboardActionListener(KeyboardActionListener listener) {
        mListener = listener;
    }

    // Returns true if keyboard has been changed by this callback.
    private boolean callListenerOnPressAndCheckKeyboardLayoutChange(Key key, boolean withSliding) {
        final boolean ignoreModifierKey = mIgnoreModifierKey && isModifierCode(key.mCode);
        if (DEBUG_LISTENER)
            Log.d(TAG, "onPress    : " + keyCodePrintable(key.mCode) + " sliding=" + withSliding
                    + " ignoreModifier=" + ignoreModifierKey);
        if (ignoreModifierKey)
            return false;
        if (key.isEnabled()) {
            mListener.onPress(key.mCode, withSliding);
            final boolean keyboardLayoutHasBeenChanged = mKeyboardLayoutHasBeenChanged;
            mKeyboardLayoutHasBeenChanged = false;
            return keyboardLayoutHasBeenChanged;
        }
        return false;
    }

    // Note that we need primaryCode argument because the keyboard may in shifted state and the
    // primaryCode is different from {@link Key#mCode}.
    private void callListenerOnCodeInput(Key key, int primaryCode, int[] keyCodes, int x, int y) {
        final boolean ignoreModifierKey = mIgnoreModifierKey && isModifierCode(key.mCode);
        if (DEBUG_LISTENER)
            Log.d(TAG, "onCodeInput: " + keyCodePrintable(primaryCode)
                    + " codes="+ Arrays.toString(keyCodes) + " x=" + x + " y=" + y
                    + " ignoreModifier=" + ignoreModifierKey);
        if (ignoreModifierKey)
            return;
        if (key.isEnabled())
            mListener.onCodeInput(primaryCode, keyCodes, x, y);
    }

    private void callListenerOnTextInput(Key key) {
        if (DEBUG_LISTENER)
            Log.d(TAG, "onTextInput: text=" + key.mOutputText);
        if (key.isEnabled())
            mListener.onTextInput(key.mOutputText);
    }

    // Note that we need primaryCode argument because the keyboard may in shifted state and the
    // primaryCode is different from {@link Key#mCode}.
    private void callListenerOnRelease(Key key, int primaryCode, boolean withSliding) {
        final boolean ignoreModifierKey = mIgnoreModifierKey && isModifierCode(key.mCode);
        if (DEBUG_LISTENER)
            Log.d(TAG, "onRelease  : " + keyCodePrintable(primaryCode) + " sliding="
                    + withSliding + " ignoreModifier=" + ignoreModifierKey);
        if (ignoreModifierKey)
            return;
        if (key.isEnabled())
            mListener.onRelease(primaryCode, withSliding);
    }

    private void callListenerOnCancelInput() {
        if (DEBUG_LISTENER)
            Log.d(TAG, "onCancelInput");
        mListener.onCancelInput();
    }

    public void setKeyDetectorInner(KeyDetector keyDetector) {
        mKeyDetector = keyDetector;
        mKeyboard = keyDetector.getKeyboard();
        mKeys = mKeyboard.getKeys();
        final int keyQuarterWidth = mKeyboard.getKeyWidth() / 4;
        mKeyQuarterWidthSquared = keyQuarterWidth * keyQuarterWidth;
    }

    public void setKeyDetector(KeyDetector keyDetector) {
        if (keyDetector == null)
            throw new NullPointerException();
        setKeyDetectorInner(keyDetector);
        // Mark that keyboard layout has been changed.
        mKeyboardLayoutHasBeenChanged = true;
    }

    public boolean isInSlidingKeyInput() {
        return mIsInSlidingKeyInput;
    }

    private boolean isValidKeyIndex(int keyIndex) {
        return keyIndex >= 0 && keyIndex < mKeys.size();
    }

    public Key getKey(int keyIndex) {
        return isValidKeyIndex(keyIndex) ? mKeys.get(keyIndex) : null;
    }

    private static boolean isModifierCode(int primaryCode) {
        return primaryCode == Keyboard.CODE_SHIFT
                || primaryCode == Keyboard.CODE_SWITCH_ALPHA_SYMBOL;
    }

    private boolean isModifierInternal(int keyIndex) {
        final Key key = getKey(keyIndex);
        return key == null ? false : isModifierCode(key.mCode);
    }

    public boolean isModifier() {
        return isModifierInternal(mKeyIndex);
    }

    private boolean isOnModifierKey(int x, int y) {
        return isModifierInternal(mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null));
    }

    public boolean isOnShiftKey(int x, int y) {
        final Key key = getKey(mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null));
        return key != null && key.mCode == Keyboard.CODE_SHIFT;
    }

    public int getKeyIndexOn(int x, int y) {
        return mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null);
    }

    public boolean isSpaceKey(int keyIndex) {
        Key key = getKey(keyIndex);
        return key != null && key.mCode == Keyboard.CODE_SPACE;
    }

    public void setReleasedKeyGraphics() {
        setReleasedKeyGraphics(mKeyIndex);
    }

    private void setReleasedKeyGraphics(int keyIndex) {
        final Key key = getKey(keyIndex);
        if (key != null) {
            key.onReleased();
            mDrawingProxy.invalidateKey(key);
        }
    }

    private void setPressedKeyGraphics(int keyIndex) {
        final Key key = getKey(keyIndex);
        if (key != null && key.isEnabled()) {
            key.onPressed();
            mDrawingProxy.invalidateKey(key);
        }
    }

    public int getLastX() {
        return mLastX;
    }

    public int getLastY() {
        return mLastY;
    }

    public long getDownTime() {
        return mDownTime;
    }

    private int onDownKey(int x, int y, long eventTime) {
        mDownTime = eventTime;
        return onMoveToNewKey(onMoveKeyInternal(x, y), x, y);
    }

    private int onMoveKeyInternal(int x, int y) {
        mLastX = x;
        mLastY = y;
        return mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null);
    }

    private int onMoveKey(int x, int y) {
        return onMoveKeyInternal(x, y);
    }

    private int onMoveToNewKey(int keyIndex, int x, int y) {
        mKeyIndex = keyIndex;
        mKeyX = x;
        mKeyY = y;
        return keyIndex;
    }

    private int onUpKey(int x, int y, long eventTime) {
        mUpTime = eventTime;
        mKeyIndex = KeyDetector.NOT_A_KEY;
        return onMoveKeyInternal(x, y);
    }

    public void onDownEvent(int x, int y, long eventTime) {
        if (DEBUG_EVENT)
            printTouchEvent("onDownEvent:", x, y, eventTime);

        // Naive up-to-down noise filter.
        final long deltaT = eventTime - mUpTime;
        if (deltaT < mTouchNoiseThresholdMillis) {
            final int dx = x - mLastX;
            final int dy = y - mLastY;
            final int distanceSquared = (dx * dx + dy * dy);
            if (distanceSquared < mTouchNoiseThresholdDistanceSquared) {
                if (DEBUG_MODE)
                    Log.w(TAG, "onDownEvent: ignore potential noise: time=" + deltaT
                            + " distance=" + distanceSquared);
                mKeyAlreadyProcessed = true;
                return;
            }
        }

        final PointerTrackerQueue queue = mPointerTrackerQueue;
        if (queue != null) {
            if (isOnModifierKey(x, y)) {
                // Before processing a down event of modifier key, all pointers already being
                // tracked should be released.
                queue.releaseAllPointers(eventTime);
            }
            queue.add(this);
        }
        onDownEventInternal(x, y, eventTime);
    }

    private void onDownEventInternal(int x, int y, long eventTime) {
        int keyIndex = onDownKey(x, y, eventTime);
        // Sliding key is allowed when 1) enabled by configuration, 2) this pointer starts sliding
        // from modifier key, or 3) this pointer is on mini-keyboard.
        mIsAllowedSlidingKeyInput = mConfigSlidingKeyInputEnabled || isModifierInternal(keyIndex)
                || mKeyDetector instanceof MiniKeyboardKeyDetector;
        mKeyboardLayoutHasBeenChanged = false;
        mKeyAlreadyProcessed = false;
        mIsRepeatableKey = false;
        mIsInSlidingKeyInput = false;
        mIsInSlidingLanguageSwitch = false;
        mIgnoreModifierKey = false;
        if (isValidKeyIndex(keyIndex)) {
            // This onPress call may have changed keyboard layout. Those cases are detected at
            // {@link #setKeyboard}. In those cases, we should update keyIndex according to the new
            // keyboard layout.
            if (callListenerOnPressAndCheckKeyboardLayoutChange(getKey(keyIndex), false))
                keyIndex = onDownKey(x, y, eventTime);

            startRepeatKey(keyIndex);
            startLongPressTimer(keyIndex);
            showKeyPreview(keyIndex);
            setPressedKeyGraphics(keyIndex);
        }
    }

    private void startSlidingKeyInput(Key key) {
        if (!mIsInSlidingKeyInput)
            mIgnoreModifierKey = isModifierCode(key.mCode);
        mIsInSlidingKeyInput = true;
    }

    public void onMoveEvent(int x, int y, long eventTime) {
        if (DEBUG_MOVE_EVENT)
            printTouchEvent("onMoveEvent:", x, y, eventTime);
        if (mKeyAlreadyProcessed)
            return;

        // TODO: Remove this hacking code
        if (mIsInSlidingLanguageSwitch) {
            ((LatinKeyboard)mKeyboard).updateSpacebarPreviewIcon(x - mKeyX);
            showKeyPreview(mSpaceKeyIndex);
            return;
        }
        final int lastX = mLastX;
        final int lastY = mLastY;
        final int oldKeyIndex = mKeyIndex;
        final Key oldKey = getKey(oldKeyIndex);
        int keyIndex = onMoveKey(x, y);
        if (isValidKeyIndex(keyIndex)) {
            if (oldKey == null) {
                // The pointer has been slid in to the new key, but the finger was not on any keys.
                // In this case, we must call onPress() to notify that the new key is being pressed.
                // This onPress call may have changed keyboard layout. Those cases are detected at
                // {@link #setKeyboard}. In those cases, we should update keyIndex according to the
                // new keyboard layout.
                if (callListenerOnPressAndCheckKeyboardLayoutChange(getKey(keyIndex), true))
                    keyIndex = onMoveKey(x, y);
                onMoveToNewKey(keyIndex, x, y);
                startLongPressTimer(keyIndex);
                showKeyPreview(keyIndex);
                setPressedKeyGraphics(keyIndex);
            } else if (isMajorEnoughMoveToBeOnNewKey(x, y, keyIndex)) {
                // The pointer has been slid in to the new key from the previous key, we must call
                // onRelease() first to notify that the previous key has been released, then call
                // onPress() to notify that the new key is being pressed.
                setReleasedKeyGraphics(oldKeyIndex);
                callListenerOnRelease(oldKey, oldKey.mCode, true);
                startSlidingKeyInput(oldKey);
                mTimerProxy.cancelKeyTimers();
                startRepeatKey(keyIndex);
                if (mIsAllowedSlidingKeyInput) {
                    // This onPress call may have changed keyboard layout. Those cases are detected
                    // at {@link #setKeyboard}. In those cases, we should update keyIndex according
                    // to the new keyboard layout.
                    if (callListenerOnPressAndCheckKeyboardLayoutChange(getKey(keyIndex), true))
                        keyIndex = onMoveKey(x, y);
                    onMoveToNewKey(keyIndex, x, y);
                    startLongPressTimer(keyIndex);
                    setPressedKeyGraphics(keyIndex);
                    showKeyPreview(keyIndex);
                } else {
                    // HACK: On some devices, quick successive touches may be translated to sudden
                    // move by touch panel firmware. This hack detects the case and translates the
                    // move event to successive up and down events.
                    final int dx = x - lastX;
                    final int dy = y - lastY;
                    final int lastMoveSquared = dx * dx + dy * dy;
                    if (lastMoveSquared >= mKeyQuarterWidthSquared) {
                        if (DEBUG_MODE)
                            Log.w(TAG, String.format("onMoveEvent: sudden move is translated to "
                                    + "up[%d,%d]/down[%d,%d] events", lastX, lastY, x, y));
                        onUpEventInternal(lastX, lastY, eventTime, true);
                        onDownEventInternal(x, y, eventTime);
                    } else {
                        mKeyAlreadyProcessed = true;
                        dismissKeyPreview();
                        setReleasedKeyGraphics(oldKeyIndex);
                    }
                }
            }
            // TODO: Remove this hack code
            else if (isSpaceKey(keyIndex) && !mIsInSlidingLanguageSwitch
                    && mKeyboard instanceof LatinKeyboard) {
                final LatinKeyboard keyboard = ((LatinKeyboard)mKeyboard);
                if (mSubtypeSwitcher.useSpacebarLanguageSwitcher()
                        && mSubtypeSwitcher.getEnabledKeyboardLocaleCount() > 1) {
                    final int diff = x - mKeyX;
                    if (keyboard.shouldTriggerSpacebarSlidingLanguageSwitch(diff)) {
                        // Detect start sliding language switch.
                        mIsInSlidingLanguageSwitch = true;
                        mSpaceKeyIndex = keyIndex;
                        keyboard.updateSpacebarPreviewIcon(diff);
                        // Display spacebar slide language switcher.
                        showKeyPreview(keyIndex);
                        final PointerTrackerQueue queue = mPointerTrackerQueue;
                        if (queue != null)
                            queue.releaseAllPointersExcept(this, eventTime, true);
                    }
                }
            }
        } else {
            if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, keyIndex)) {
                // The pointer has been slid out from the previous key, we must call onRelease() to
                // notify that the previous key has been released.
                setReleasedKeyGraphics(oldKeyIndex);
                callListenerOnRelease(oldKey, oldKey.mCode, true);
                startSlidingKeyInput(oldKey);
                mTimerProxy.cancelLongPressTimers();
                if (mIsAllowedSlidingKeyInput) {
                    onMoveToNewKey(keyIndex, x, y);
                } else {
                    mKeyAlreadyProcessed = true;
                    dismissKeyPreview();
                }
            }
        }
    }

    public void onUpEvent(int x, int y, long eventTime) {
        if (DEBUG_EVENT)
            printTouchEvent("onUpEvent  :", x, y, eventTime);

        final PointerTrackerQueue queue = mPointerTrackerQueue;
        if (queue != null) {
            if (isModifier()) {
                // Before processing an up event of modifier key, all pointers already being
                // tracked should be released.
                queue.releaseAllPointersExcept(this, eventTime, true);
            } else {
                queue.releaseAllPointersOlderThan(this, eventTime);
            }
            queue.remove(this);
        }
        onUpEventInternal(x, y, eventTime, true);
    }

    // Let this pointer tracker know that one of newer-than-this pointer trackers got an up event.
    // This pointer tracker needs to keep the key top graphics "pressed", but needs to get a
    // "virtual" up event.
    public void onPhantomUpEvent(int x, int y, long eventTime, boolean updateReleasedKeyGraphics) {
        if (DEBUG_EVENT)
            printTouchEvent("onPhntEvent:", x, y, eventTime);
        onUpEventInternal(x, y, eventTime, updateReleasedKeyGraphics);
        mKeyAlreadyProcessed = true;
    }

    private void onUpEventInternal(int x, int y, long eventTime,
            boolean updateReleasedKeyGraphics) {
        mTimerProxy.cancelKeyTimers();
        mDrawingProxy.cancelShowKeyPreview(this);
        mIsInSlidingKeyInput = false;
        final int keyX, keyY;
        if (isMajorEnoughMoveToBeOnNewKey(x, y, onMoveKey(x, y))) {
            keyX = x;
            keyY = y;
        } else {
            // Use previous fixed key coordinates.
            keyX = mKeyX;
            keyY = mKeyY;
        }
        final int keyIndex = onUpKey(keyX, keyY, eventTime);
        dismissKeyPreview();
        if (updateReleasedKeyGraphics)
            setReleasedKeyGraphics(keyIndex);
        if (mKeyAlreadyProcessed)
            return;
        // TODO: Remove this hacking code
        if (mIsInSlidingLanguageSwitch) {
            setReleasedKeyGraphics(mSpaceKeyIndex);
            final int languageDir = ((LatinKeyboard)mKeyboard).getLanguageChangeDirection();
            if (languageDir != 0) {
                final int code = (languageDir == 1)
                        ? LatinKeyboard.CODE_NEXT_LANGUAGE : LatinKeyboard.CODE_PREV_LANGUAGE;
                // This will change keyboard layout.
                mListener.onCodeInput(code, new int[] {code}, keyX, keyY);
            }
            mIsInSlidingLanguageSwitch = false;
            ((LatinKeyboard)mKeyboard).setSpacebarSlidingLanguageSwitchDiff(0);
            return;
        }
        if (!mIsRepeatableKey) {
            detectAndSendKey(keyIndex, keyX, keyY);
        }
    }

    public void onLongPressed() {
        mKeyAlreadyProcessed = true;
        final PointerTrackerQueue queue = mPointerTrackerQueue;
        if (queue != null) {
            // TODO: Support chording + long-press input.
            queue.releaseAllPointersExcept(this, SystemClock.uptimeMillis(), true);
            queue.remove(this);
        }
    }

    public void onCancelEvent(int x, int y, long eventTime) {
        if (DEBUG_EVENT)
            printTouchEvent("onCancelEvt:", x, y, eventTime);

        final PointerTrackerQueue queue = mPointerTrackerQueue;
        if (queue != null) {
            queue.releaseAllPointersExcept(this, eventTime, true);
            queue.remove(this);
        }
        onCancelEventInternal();
    }

    private void onCancelEventInternal() {
        mTimerProxy.cancelKeyTimers();
        mDrawingProxy.cancelShowKeyPreview(this);
        dismissKeyPreview();
        setReleasedKeyGraphics(mKeyIndex);
        mIsInSlidingKeyInput = false;
    }

    private void startRepeatKey(int keyIndex) {
        final Key key = getKey(keyIndex);
        if (key != null && key.mRepeatable) {
            dismissKeyPreview();
            onRepeatKey(keyIndex);
            mTimerProxy.startKeyRepeatTimer(mDelayBeforeKeyRepeatStart, keyIndex, this);
            mIsRepeatableKey = true;
        } else {
            mIsRepeatableKey = false;
        }
    }

    public void onRepeatKey(int keyIndex) {
        Key key = getKey(keyIndex);
        if (key != null) {
            detectAndSendKey(keyIndex, key.mX, key.mY);
        }
    }

    private boolean isMajorEnoughMoveToBeOnNewKey(int x, int y, int newKey) {
        if (mKeys == null || mKeyDetector == null)
            throw new NullPointerException("keyboard and/or key detector not set");
        int curKey = mKeyIndex;
        if (newKey == curKey) {
            return false;
        } else if (isValidKeyIndex(curKey)) {
            return mKeys.get(curKey).squaredDistanceToEdge(x, y)
                    >= mKeyDetector.getKeyHysteresisDistanceSquared();
        } else {
            return true;
        }
    }

    // The modifier key, such as shift key, should not show its key preview.
    private boolean isKeyPreviewNotRequired(int keyIndex) {
        final Key key = getKey(keyIndex);
        if (key == null || !key.isEnabled())
            return true;
        // Such as spacebar sliding language switch.
        if (mKeyboard.needSpacebarPreview(keyIndex))
            return false;
        final int code = key.mCode;
        return isModifierCode(code) || code == Keyboard.CODE_DELETE
                || code == Keyboard.CODE_ENTER || code == Keyboard.CODE_SPACE;
    }

    private void showKeyPreview(int keyIndex) {
        if (isKeyPreviewNotRequired(keyIndex))
            return;
        mDrawingProxy.showKeyPreview(keyIndex, this);
    }

    private void dismissKeyPreview() {
        mDrawingProxy.dismissKeyPreview(this);
    }

    private void startLongPressTimer(int keyIndex) {
        Key key = getKey(keyIndex);
        if (key.mCode == Keyboard.CODE_SHIFT) {
            mTimerProxy.startLongPressShiftTimer(mLongPressShiftKeyTimeout, keyIndex, this);
        } else if (key.hasUppercaseLetter() && mKeyboard.isManualTemporaryUpperCase()) {
            // We need not start long press timer on the key which has manual temporary upper case
            // code defined and the keyboard is in manual temporary upper case mode.
            return;
        } else if (mKeyboardSwitcher.isInMomentarySwitchState()) {
            // We use longer timeout for sliding finger input started from the symbols mode key.
            mTimerProxy.startLongPressTimer(mLongPressKeyTimeout * 3, keyIndex, this);
        } else {
            mTimerProxy.startLongPressTimer(mLongPressKeyTimeout, keyIndex, this);
        }
    }

    private void detectAndSendKey(int index, int x, int y) {
        final Key key = getKey(index);
        if (key == null) {
            callListenerOnCancelInput();
            return;
        }
        if (key.mOutputText != null) {
            callListenerOnTextInput(key);
            callListenerOnRelease(key, key.mCode, false);
        } else {
            int code = key.mCode;
            final int[] codes = mKeyDetector.newCodeArray();
            mKeyDetector.getKeyIndexAndNearbyCodes(x, y, codes);

            // If keyboard is in manual temporary upper case state and key has manual temporary
            // uppercase letter as key hint letter, alternate character code should be sent.
            if (mKeyboard.isManualTemporaryUpperCase() && key.hasUppercaseLetter()) {
                code = key.mHintLabel.charAt(0);
                codes[0] = code;
            }

            // Swap the first and second values in the codes array if the primary code is not the
            // first value but the second value in the array. This happens when key debouncing is
            // in effect.
            if (codes.length >= 2 && codes[0] != code && codes[1] == code) {
                codes[1] = codes[0];
                codes[0] = code;
            }
            callListenerOnCodeInput(key, code, codes, x, y);
            callListenerOnRelease(key, code, false);
        }
    }

    private long mPreviousEventTime;

    private void printTouchEvent(String title, int x, int y, long eventTime) {
        final int keyIndex = mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null);
        final Key key = getKey(keyIndex);
        final String code = (key == null) ? "----" : keyCodePrintable(key.mCode);
        final long delta = eventTime - mPreviousEventTime;
        Log.d(TAG, String.format("%s%s[%d] %4d %4d %5d %3d(%s)", title,
                (mKeyAlreadyProcessed ? "-" : " "), mPointerId, x, y, delta, keyIndex, code));
        mPreviousEventTime = eventTime;
    }

    private static String keyCodePrintable(int primaryCode) {
        final String modifier = isModifierCode(primaryCode) ? " modifier" : "";
        return  String.format((primaryCode < 0) ? "%4d" : "0x%02x", primaryCode) + modifier;
    }
}
