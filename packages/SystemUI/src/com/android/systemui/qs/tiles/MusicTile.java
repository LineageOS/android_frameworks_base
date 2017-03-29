package com.android.systemui.qs.tiles;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.Collections;
import java.util.List;

@SuppressLint("NewApi")
public class MusicTile extends QSTile<QSTile.State> {

    private final MusicDetailAdapter mDetailAdapter;
    private final MediaSessionManager mMediaManager;
    private final CharSequence mTileLabelDefault;
    private MediaController mController;
    private CharSequence mTileLabel;
    private int mLastPlaybackState;
    private CharSequence mQueueTitle;

    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
            mLastPlaybackState = state.getState();
            refreshState();
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) {
            mQueueTitle = title;
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            mDetailAdapter.mAdapter.updateQueue(queue, mController);
            notifyAdapter();
        }
    };

    public MusicTile(Host host) {
        super(host);
        mDetailAdapter = new MusicDetailAdapter();
        mMediaManager = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        mTileLabelDefault = getHost().getContext().getString(R.string.quick_settings_music_label);
        mTileLabel = mTileLabelDefault;
        refreshState();
    }

    private void updateMediaController() {
        if (mController != null) {
            mController.unregisterCallback(mMediaCallback);
        }
        List<MediaController> sessions = mMediaManager.getActiveSessions(null);
        if (sessions.size() > 0) {
            mController = sessions.get(0);
            mLastPlaybackState = mController.getPlaybackState() != null ? mController.getPlaybackState().getState() : PlaybackState.STATE_NONE;
            mQueueTitle = mController.getQueueTitle();
            mDetailAdapter.mAdapter.updateQueue(mController.getQueue(), mController);
            mController.registerCallback(mMediaCallback);
        } else {
            mController = null;
            mLastPlaybackState = PlaybackState.STATE_NONE;
            mQueueTitle = null;
            mDetailAdapter.mAdapter.updateQueue(Collections.<MediaSession.QueueItem>emptyList(), mController);
        }
    }

    private void notifyAdapter() {
        if (mDetailAdapter.mQueueItemsRecycler != null) {
            mDetailAdapter.mQueueItemsRecycler.post(new Runnable() {
                @Override
                public void run() {
                    mDetailAdapter.mAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick() {
        MetricsLogger.action(mContext, getMetricsCategory(), "expand");
        refreshState();
        if (mController != null) {
            showDetail(true);
        } else signalNoPlayback();
    }

    @Override
    protected void handleSecondaryClick() {
        MetricsLogger.action(mContext, getMetricsCategory(), "state");
        if (mController != null) {
            MediaController.TransportControls controls = mController.getTransportControls();
            switch (mLastPlaybackState) {
                case PlaybackState.STATE_PLAYING:
                case PlaybackState.STATE_BUFFERING:
                    controls.pause();
                    break;
                case PlaybackState.STATE_NONE:
                case PlaybackState.STATE_PAUSED:
                case PlaybackState.STATE_STOPPED:
                    controls.play();
                    break;
            }
        } else signalNoPlayback();
    }

    private void signalNoPlayback() {
        mTileLabel = getHost().getContext().getString(R.string.quick_settings_music_label_no_playback);
        refreshState();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mTileLabel = mTileLabelDefault;
                refreshState();
            }
        }, 2000);
    }

    @Override
    public Intent getLongClickIntent() {
        if (mController != null) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setPackage(mController.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            return i;
        } else return new Intent();
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        MetricsLogger.action(mContext, getMetricsCategory(), "update");
        updateMediaController();

        int qsIconId;
        switch (mLastPlaybackState) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
                qsIconId = R.drawable.ic_qs_music_pause;
                break;
            default:
                qsIconId = R.drawable.ic_qs_music_play_arrow;
                break;
        }
        state.icon = new DrawableIcon(mHost.getContext().getResources().getDrawable(qsIconId));
        state.label = getTileLabel();
    }

    @Override
    public CharSequence getTileLabel() {
        return mTileLabel;
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected void setListening(boolean listening) {
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_MUSIC;
    }

    public final class MusicDetailAdapter implements DetailAdapter {

        private RecyclerView mQueueItemsRecycler;
        private MusicQueueAdapter mAdapter = new MusicQueueAdapter();
        private ViewParent mNotificationPanel;

        @Override
        public CharSequence getTitle() {
            return mQueueTitle != null ? mQueueTitle : "";
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @SuppressLint("NewApi")
        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mQueueItemsRecycler = new RecyclerView(context) {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent e) {
                    if (mNotificationPanel != null)
                        mNotificationPanel.requestDisallowInterceptTouchEvent(true);
                    return super.onInterceptTouchEvent(e);
                }

                @Override
                protected void onLayout(boolean changed, int l, int t, int r, int b) {
                    getLayoutParams().height = ((ViewGroup) getParent()).getMeasuredHeight();
                    super.onLayout(changed, l, t, r, b);
                }
            };
            mQueueItemsRecycler.setLayoutManager(new LinearLayoutManager(context));
            mQueueItemsRecycler.setItemAnimator(new DefaultItemAnimator());

            mQueueItemsRecycler.setAdapter(mAdapter);
            mQueueItemsRecycler.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // TODO not an ideal solution, even though it works.
            ViewParent current = parent.getParent();
            while (!NotificationPanelView.class.isInstance(current)) {
                current = current.getParent();
            }
            mNotificationPanel = current;
            return mQueueItemsRecycler;
        }

        @Override
        public Intent getSettingsIntent() {
            return null;
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_MUSIC_DETAILS;
        }
    }
}