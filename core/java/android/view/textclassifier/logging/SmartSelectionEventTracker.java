/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.textclassifier.logging;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.metrics.LogMaker;
import android.util.Log;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextSelection;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.UUID;

/**
 * A selection event tracker.
 * @hide
 */
//TODO: Do not allow any crashes from this class.
public final class SmartSelectionEventTracker {

    private static final String LOG_TAG = "SmartSelectEventTracker";
    private static final boolean DEBUG_LOG_ENABLED = false;

    private static final int START_EVENT_DELTA = MetricsEvent.FIELD_SELECTION_SINCE_START;
    private static final int PREV_EVENT_DELTA = MetricsEvent.FIELD_SELECTION_SINCE_PREVIOUS;
    private static final int INDEX = MetricsEvent.FIELD_SELECTION_SESSION_INDEX;
    private static final int VERSION_TAG = MetricsEvent.FIELD_SELECTION_VERSION_TAG;
    private static final int SMART_INDICES = MetricsEvent.FIELD_SELECTION_SMART_RANGE;
    private static final int EVENT_INDICES = MetricsEvent.FIELD_SELECTION_RANGE;
    private static final int SESSION_ID = MetricsEvent.FIELD_SELECTION_SESSION_ID;

    private static final String ZERO = "0";
    private static final String TEXTVIEW = "textview";
    private static final String EDITTEXT = "edittext";
    private static final String WEBVIEW = "webview";
    private static final String EDIT_WEBVIEW = "edit-webview";
    private static final String UNKNOWN = "unknown";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({WidgetType.UNSPECIFIED, WidgetType.TEXTVIEW, WidgetType.WEBVIEW,
            WidgetType.EDITTEXT, WidgetType.EDIT_WEBVIEW})
    public @interface WidgetType {
    int UNSPECIFIED = 0;
    int TEXTVIEW = 1;
    int WEBVIEW = 2;
    int EDITTEXT = 3;
    int EDIT_WEBVIEW = 4;
    }

    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final int mWidgetType;
    private final Context mContext;

    @Nullable private String mSessionId;
    private final int[] mSmartIndices = new int[2];
    private final int[] mPrevIndices = new int[2];
    private int mOrigStart;
    private int mIndex;
    private long mSessionStartTime;
    private long mLastEventTime;
    private boolean mSmartSelectionTriggered;
    private String mVersionTag;

    public SmartSelectionEventTracker(@NonNull Context context, @WidgetType int widgetType) {
        mWidgetType = widgetType;
        mContext = Preconditions.checkNotNull(context);
    }

    /**
     * Logs a selection event.
     *
     * @param event the selection event
     */
    public void logEvent(@NonNull SelectionEvent event) {
        Preconditions.checkNotNull(event);

        if (event.mEventType != SelectionEvent.EventType.SELECTION_STARTED && mSessionId == null
                && DEBUG_LOG_ENABLED) {
            Log.d(LOG_TAG, "Selection session not yet started. Ignoring event");
            return;
        }

        final long now = System.currentTimeMillis();
        switch (event.mEventType) {
            case SelectionEvent.EventType.SELECTION_STARTED:
                mSessionId = startNewSession();
                Preconditions.checkArgument(event.mEnd == event.mStart + 1);
                mOrigStart = event.mStart;
                mSessionStartTime = now;
                break;
            case SelectionEvent.EventType.SMART_SELECTION_SINGLE:  // fall through
            case SelectionEvent.EventType.SMART_SELECTION_MULTI:
                mSmartSelectionTriggered = true;
                mVersionTag = getVersionTag(event);
                mSmartIndices[0] = event.mStart;
                mSmartIndices[1] = event.mEnd;
                break;
            case SelectionEvent.EventType.SELECTION_MODIFIED:  // fall through
            case SelectionEvent.EventType.AUTO_SELECTION:
                if (mPrevIndices[0] == event.mStart && mPrevIndices[1] == event.mEnd) {
                    // Selection did not change. Ignore event.
                    return;
                }
        }
        writeEvent(event, now);

        if (event.isTerminal()) {
            endSession();
        }
    }

    private void writeEvent(SelectionEvent event, long now) {
        final long prevEventDelta = mLastEventTime == 0 ? 0 : now - mLastEventTime;
        final LogMaker log = new LogMaker(MetricsEvent.TEXT_SELECTION_SESSION)
                .setType(getLogType(event))
                .setSubtype(getLogSubType(event))
                .setPackageName(mContext.getPackageName())
                .setTimestamp(now)
                .addTaggedData(START_EVENT_DELTA, now - mSessionStartTime)
                .addTaggedData(PREV_EVENT_DELTA, prevEventDelta)
                .addTaggedData(INDEX, mIndex)
                .addTaggedData(VERSION_TAG, mVersionTag)
                .addTaggedData(SMART_INDICES, getSmartDelta())
                .addTaggedData(EVENT_INDICES, getEventDelta(event))
                .addTaggedData(SESSION_ID, mSessionId);
        mMetricsLogger.write(log);
        debugLog(log);
        mLastEventTime = now;
        mPrevIndices[0] = event.mStart;
        mPrevIndices[1] = event.mEnd;
        mIndex++;
    }

    private String startNewSession() {
        endSession();
        mSessionId = createSessionId();
        return mSessionId;
    }

    private void endSession() {
        // Reset fields.
        mOrigStart = 0;
        mSmartIndices[0] = mSmartIndices[1] = 0;
        mPrevIndices[0] = mPrevIndices[1] = 0;
        mIndex = 0;
        mSessionStartTime = 0;
        mLastEventTime = 0;
        mSmartSelectionTriggered = false;
        mVersionTag = getVersionTag(null);
        mSessionId = null;
    }

    private static int getLogType(SelectionEvent event) {
        switch (event.mEventType) {
            case SelectionEvent.ActionType.OVERTYPE:
                return MetricsEvent.ACTION_TEXT_SELECTION_OVERTYPE;
            case SelectionEvent.ActionType.COPY:
                return MetricsEvent.ACTION_TEXT_SELECTION_COPY;
            case SelectionEvent.ActionType.PASTE:
                return MetricsEvent.ACTION_TEXT_SELECTION_PASTE;
            case SelectionEvent.ActionType.CUT:
                return MetricsEvent.ACTION_TEXT_SELECTION_CUT;
            case SelectionEvent.ActionType.SHARE:
                return MetricsEvent.ACTION_TEXT_SELECTION_SHARE;
            case SelectionEvent.ActionType.SMART_SHARE:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE;
            case SelectionEvent.ActionType.DRAG:
                return MetricsEvent.ACTION_TEXT_SELECTION_DRAG;
            case SelectionEvent.ActionType.ABANDON:
                return MetricsEvent.ACTION_TEXT_SELECTION_ABANDON;
            case SelectionEvent.ActionType.OTHER:
                return MetricsEvent.ACTION_TEXT_SELECTION_OTHER;
            case SelectionEvent.ActionType.SELECT_ALL:
                return MetricsEvent.ACTION_TEXT_SELECTION_SELECT_ALL;
            case SelectionEvent.ActionType.RESET:
                return MetricsEvent.ACTION_TEXT_SELECTION_RESET;
            case SelectionEvent.EventType.SELECTION_STARTED:
                return MetricsEvent.ACTION_TEXT_SELECTION_START;
            case SelectionEvent.EventType.SELECTION_MODIFIED:
                return MetricsEvent.ACTION_TEXT_SELECTION_MODIFY;
            case SelectionEvent.EventType.SMART_SELECTION_SINGLE:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_SINGLE;
            case SelectionEvent.EventType.SMART_SELECTION_MULTI:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_MULTI;
            case SelectionEvent.EventType.AUTO_SELECTION:
                return MetricsEvent.ACTION_TEXT_SELECTION_AUTO;
            default:
                return MetricsEvent.VIEW_UNKNOWN;
        }
    }

    private static String getLogTypeString(int logType) {
        switch (logType) {
            case MetricsEvent.ACTION_TEXT_SELECTION_OVERTYPE:
                return "OVERTYPE";
            case MetricsEvent.ACTION_TEXT_SELECTION_COPY:
                return "COPY";
            case MetricsEvent.ACTION_TEXT_SELECTION_PASTE:
                return "PASTE";
            case MetricsEvent.ACTION_TEXT_SELECTION_CUT:
                return "CUT";
            case MetricsEvent.ACTION_TEXT_SELECTION_SHARE:
                return "SHARE";
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE:
                return "SMART_SHARE";
            case MetricsEvent.ACTION_TEXT_SELECTION_DRAG:
                return "DRAG";
            case MetricsEvent.ACTION_TEXT_SELECTION_ABANDON:
                return "ABANDON";
            case MetricsEvent.ACTION_TEXT_SELECTION_OTHER:
                return "OTHER";
            case MetricsEvent.ACTION_TEXT_SELECTION_SELECT_ALL:
                return "SELECT_ALL";
            case MetricsEvent.ACTION_TEXT_SELECTION_RESET:
                return "RESET";
            case MetricsEvent.ACTION_TEXT_SELECTION_START:
                return "SELECTION_STARTED";
            case MetricsEvent.ACTION_TEXT_SELECTION_MODIFY:
                return "SELECTION_MODIFIED";
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_SINGLE:
                return "SMART_SELECTION_SINGLE";
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_MULTI:
                return "SMART_SELECTION_MULTI";
            case MetricsEvent.ACTION_TEXT_SELECTION_AUTO:
                return "AUTO_SELECTION";
            default:
                return UNKNOWN;
        }
    }

    private static int getLogSubType(SelectionEvent event) {
        switch (event.mEntityType) {
            case TextClassifier.TYPE_OTHER:
                return MetricsEvent.TEXT_CLASSIFIER_TYPE_OTHER;
            case TextClassifier.TYPE_EMAIL:
                return MetricsEvent.TEXT_CLASSIFIER_TYPE_EMAIL;
            case TextClassifier.TYPE_PHONE:
                return MetricsEvent.TEXT_CLASSIFIER_TYPE_PHONE;
            case TextClassifier.TYPE_ADDRESS:
                return MetricsEvent.TEXT_CLASSIFIER_TYPE_ADDRESS;
            case TextClassifier.TYPE_URL:
                return MetricsEvent.TEXT_CLASSIFIER_TYPE_URL;
            default:
                return MetricsEvent.TEXT_CLASSIFIER_TYPE_UNKNOWN;
        }
    }

    private static String getLogSubTypeString(int logSubType) {
        switch (logSubType) {
            case MetricsEvent.TEXT_CLASSIFIER_TYPE_OTHER:
                return TextClassifier.TYPE_OTHER;
            case MetricsEvent.TEXT_CLASSIFIER_TYPE_EMAIL:
                return TextClassifier.TYPE_EMAIL;
            case MetricsEvent.TEXT_CLASSIFIER_TYPE_PHONE:
                return TextClassifier.TYPE_PHONE;
            case MetricsEvent.TEXT_CLASSIFIER_TYPE_ADDRESS:
                return TextClassifier.TYPE_ADDRESS;
            case MetricsEvent.TEXT_CLASSIFIER_TYPE_URL:
                return TextClassifier.TYPE_URL;
            default:
                return TextClassifier.TYPE_UNKNOWN;
        }
    }

    private int getSmartDelta() {
        if (mSmartSelectionTriggered) {
            return (clamp(mSmartIndices[0] - mOrigStart) << 16)
                    | (clamp(mSmartIndices[1] - mOrigStart) & 0xffff);
        }
        // If the smart selection model was not run, return invalid selection indices [0,0]. This
        // allows us to tell from the terminal event alone whether the model was run.
        return 0;
    }

    private int getEventDelta(SelectionEvent event) {
        return (clamp(event.mStart - mOrigStart) << 16)
                | (clamp(event.mEnd - mOrigStart) & 0xffff);
    }

    private String getVersionTag(@Nullable SelectionEvent event) {
        final String widgetType;
        switch (mWidgetType) {
            case WidgetType.TEXTVIEW:
                widgetType = TEXTVIEW;
                break;
            case WidgetType.WEBVIEW:
                widgetType = WEBVIEW;
                break;
            case WidgetType.EDITTEXT:
                widgetType = EDITTEXT;
                break;
            case WidgetType.EDIT_WEBVIEW:
                widgetType = EDIT_WEBVIEW;
                break;
            default:
                widgetType = UNKNOWN;
        }
        final String version = event == null
                ? SelectionEvent.NO_VERSION_TAG
                : Objects.toString(event.mVersionTag, SelectionEvent.NO_VERSION_TAG);
        return String.format("%s/%s", widgetType, version);
    }

    private static String createSessionId() {
        return UUID.randomUUID().toString();
    }

    private static int clamp(int val) {
        return Math.max(Math.min(val, Short.MAX_VALUE), Short.MIN_VALUE);
    }

    private static void debugLog(LogMaker log) {
        if (!DEBUG_LOG_ENABLED) return;

        final String tag = Objects.toString(log.getTaggedData(VERSION_TAG), "tag");
        final int index = Integer.parseInt(Objects.toString(log.getTaggedData(INDEX), ZERO));
        if (log.getType() == MetricsEvent.ACTION_TEXT_SELECTION_START) {
            String sessionId = Objects.toString(log.getTaggedData(SESSION_ID), "");
            sessionId = sessionId.substring(sessionId.lastIndexOf("-") + 1);
            Log.d(LOG_TAG, String.format("New selection session: %s(%s)", tag, sessionId));
        }

        final String type = getLogTypeString(log.getType());
        final String subType = getLogSubTypeString(log.getSubtype());

        final int smartIndices = Integer.parseInt(
                Objects.toString(log.getTaggedData(SMART_INDICES), ZERO));
        final int smartStart = (short) ((smartIndices & 0xffff0000) >> 16);
        final int smartEnd = (short) (smartIndices & 0xffff);

        final int eventIndices = Integer.parseInt(
                Objects.toString(log.getTaggedData(EVENT_INDICES), ZERO));
        final int eventStart = (short) ((eventIndices & 0xffff0000) >> 16);
        final int eventEnd = (short) (eventIndices & 0xffff);

        Log.d(LOG_TAG, String.format("%2d: %s/%s, context=%d,%d - old=%d,%d (%s)",
                index, type, subType, eventStart, eventEnd, smartStart, smartEnd, tag));
    }

    /**
     * A selection event.
     * Specify index parameters as word token indices.
     */
    public static final class SelectionEvent {

        /**
         * Use this to specify an indeterminate positive index.
         */
        public static final int OUT_OF_BOUNDS = Short.MAX_VALUE;

        /**
         * Use this to specify an indeterminate negative index.
         */
        public static final int OUT_OF_BOUNDS_NEGATIVE = Short.MIN_VALUE;

        private static final String NO_VERSION_TAG = "";

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({ActionType.OVERTYPE, ActionType.COPY, ActionType.PASTE, ActionType.CUT,
                ActionType.SHARE, ActionType.SMART_SHARE, ActionType.DRAG, ActionType.ABANDON,
                ActionType.OTHER, ActionType.SELECT_ALL, ActionType.RESET})
        public @interface ActionType {
        /** User typed over the selection. */
        int OVERTYPE = 100;
        /** User copied the selection. */
        int COPY = 101;
        /** User pasted over the selection. */
        int PASTE = 102;
        /** User cut the selection. */
        int CUT = 103;
        /** User shared the selection. */
        int SHARE = 104;
        /** User clicked the textAssist menu item. */
        int SMART_SHARE = 105;
        /** User dragged+dropped the selection. */
        int DRAG = 106;
        /** User abandoned the selection. */
        int ABANDON = 107;
        /** User performed an action on the selection. */
        int OTHER = 108;

        /* Non-terminal actions. */
        /** User activated Select All */
        int SELECT_ALL = 200;
        /** User reset the smart selection. */
        int RESET = 201;
        }

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({ActionType.OVERTYPE, ActionType.COPY, ActionType.PASTE, ActionType.CUT,
                ActionType.SHARE, ActionType.SMART_SHARE, ActionType.DRAG, ActionType.ABANDON,
                ActionType.OTHER, ActionType.SELECT_ALL, ActionType.RESET,
                EventType.SELECTION_STARTED, EventType.SELECTION_MODIFIED,
                EventType.SMART_SELECTION_SINGLE, EventType.SMART_SELECTION_MULTI,
                EventType.AUTO_SELECTION})
        private @interface EventType {
        /** User started a new selection. */
        int SELECTION_STARTED = 1;
        /** User modified an existing selection. */
        int SELECTION_MODIFIED = 2;
        /** Smart selection triggered for a single token (word). */
        int SMART_SELECTION_SINGLE = 3;
        /** Smart selection triggered spanning multiple tokens (words). */
        int SMART_SELECTION_MULTI = 4;
        /** Something else other than User or the default TextClassifier triggered a selection. */
        int AUTO_SELECTION = 5;
        }

        private final int mStart;
        private final int mEnd;
        private @EventType int mEventType;
        private final @TextClassifier.EntityType String mEntityType;
        private final String mVersionTag;

        private SelectionEvent(
                int start, int end, int eventType,
                @TextClassifier.EntityType String entityType, String versionTag) {
            Preconditions.checkArgument(end >= start, "end cannot be less than start");
            mStart = start;
            mEnd = end;
            mEventType = eventType;
            mEntityType = Preconditions.checkNotNull(entityType);
            mVersionTag = Preconditions.checkNotNull(versionTag);
        }

        /**
         * Creates a "selection started" event.
         *
         * @param start  the word index of the selected word
         */
        public static SelectionEvent selectionStarted(int start) {
            return new SelectionEvent(
                    start, start + 1, EventType.SELECTION_STARTED,
                    TextClassifier.TYPE_UNKNOWN, NO_VERSION_TAG);
        }

        /**
         * Creates a "selection modified" event.
         * Use when the user modifies the selection.
         *
         * @param start  the start word (inclusive) index of the selection
         * @param end  the end word (exclusive) index of the selection
         */
        public static SelectionEvent selectionModified(int start, int end) {
            return new SelectionEvent(
                    start, end, EventType.SELECTION_MODIFIED,
                    TextClassifier.TYPE_UNKNOWN, NO_VERSION_TAG);
        }

        /**
         * Creates a "selection modified" event.
         * Use when the user modifies the selection and the selection's entity type is known.
         *
         * @param start  the start word (inclusive) index of the selection
         * @param end  the end word (exclusive) index of the selection
         * @param classification  the TextClassification object returned by the TextClassifier that
         *      classified the selected text
         */
        public static SelectionEvent selectionModified(
                int start, int end, @NonNull TextClassification classification) {
            final String entityType = classification.getEntityCount() > 0
                    ? classification.getEntity(0)
                    : TextClassifier.TYPE_UNKNOWN;
            final String versionTag = classification.getVersionInfo();
            return new SelectionEvent(
                    start, end, EventType.SELECTION_MODIFIED, entityType, versionTag);
        }

        /**
         * Creates a "selection modified" event.
         * Use when a TextClassifier modifies the selection.
         *
         * @param start  the start word (inclusive) index of the selection
         * @param end  the end word (exclusive) index of the selection
         * @param selection  the TextSelection object returned by the TextClassifier for the
         *      specified selection
         */
        public static SelectionEvent selectionModified(
                int start, int end, @NonNull TextSelection selection) {
            final boolean smartSelection = selection.getSourceClassifier()
                    .equals(TextClassifier.DEFAULT_LOG_TAG);
            final int eventType;
            if (smartSelection) {
                eventType = end - start > 1
                        ? EventType.SMART_SELECTION_MULTI
                        : EventType.SMART_SELECTION_SINGLE;

            } else {
                eventType = EventType.AUTO_SELECTION;
            }
            final String entityType = selection.getEntityCount() > 0
                    ? selection.getEntity(0)
                    : TextClassifier.TYPE_UNKNOWN;
            final String versionTag = selection.getVersionInfo();
            return new SelectionEvent(start, end, eventType, entityType, versionTag);
        }

        /**
         * Creates an event specifying an action taken on a selection.
         * Use when the user clicks on an action to act on the selected text.
         *
         * @param start  the start word (inclusive) index of the selection
         * @param end  the end word (exclusive) index of the selection
         * @param actionType  the action that was performed on the selection
         */
        public static SelectionEvent selectionAction(
                int start, int end, @ActionType int actionType) {
            return new SelectionEvent(
                    start, end, actionType, TextClassifier.TYPE_UNKNOWN, NO_VERSION_TAG);
        }

        /**
         * Creates an event specifying an action taken on a selection.
         * Use when the user clicks on an action to act on the selected text and the selection's
         * entity type is known.
         *
         * @param start  the start word (inclusive) index of the selection
         * @param end  the end word (exclusive) index of the selection
         * @param actionType  the action that was performed on the selection
         * @param classification  the TextClassification object returned by the TextClassifier that
         *      classified the selected text
         */
        public static SelectionEvent selectionAction(
                int start, int end, @ActionType int actionType,
                @NonNull TextClassification classification) {
            final String entityType = classification.getEntityCount() > 0
                    ? classification.getEntity(0)
                    : TextClassifier.TYPE_UNKNOWN;
            final String versionTag = classification.getVersionInfo();
            return new SelectionEvent(start, end, actionType, entityType, versionTag);
        }

        private boolean isActionType() {
            switch (mEventType) {
                case ActionType.OVERTYPE:  // fall through
                case ActionType.COPY:  // fall through
                case ActionType.PASTE:  // fall through
                case ActionType.CUT:  // fall through
                case ActionType.SHARE:  // fall through
                case ActionType.SMART_SHARE:  // fall through
                case ActionType.DRAG:  // fall through
                case ActionType.ABANDON:  // fall through
                case ActionType.SELECT_ALL:  // fall through
                case ActionType.RESET:  // fall through
                    return true;
                default:
                    return false;
            }
        }

        private boolean isTerminal() {
            switch (mEventType) {
                case ActionType.OVERTYPE:  // fall through
                case ActionType.COPY:  // fall through
                case ActionType.PASTE:  // fall through
                case ActionType.CUT:  // fall through
                case ActionType.SHARE:  // fall through
                case ActionType.SMART_SHARE:  // fall through
                case ActionType.DRAG:  // fall through
                case ActionType.ABANDON:  // fall through
                case ActionType.OTHER:  // fall through
                    return true;
                default:
                    return false;
            }
        }
    }
}
