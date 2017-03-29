package com.android.systemui.qs.tiles;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class MusicQueueAdapter extends RecyclerView.Adapter<MusicQueueAdapter.QueueItemHolder> {

    private List<MediaSession.QueueItem> mQueue = new ArrayList<>();
    private MediaController mController;

    private HashMap<Uri, WeakReference<Bitmap>> bitmapCache = new HashMap<>();

    public void updateQueue(List<MediaSession.QueueItem> newQueue, MediaController controller) {
        mQueue.clear();
        mQueue.addAll(newQueue);
        mController = controller;
    }

    @Override
    public QueueItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new QueueItemHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.qs_music_queue_item, parent, false));
    }

    @Override
    public void onBindViewHolder(QueueItemHolder holder, int position) {
        final MediaSession.QueueItem item = mQueue.get(position);
        final MediaDescription description = item.getDescription();

        Bitmap icon;
        Uri iconUri = description.getIconUri();
        WeakReference<Bitmap> cachedReference = bitmapCache.get(iconUri);
        if (cachedReference != null && cachedReference.get() != null) {
            icon = cachedReference.get();
        } else {
            if (description.getIconBitmap() != null) {
                icon = description.getIconBitmap();
            } else {
                try {
                    icon = MediaStore.Images.Media.getBitmap(holder.itemView.getContext().getContentResolver(), iconUri);
                } catch (IOException e) {
                    e.printStackTrace();
                    icon = null;
                }
            }
            bitmapCache.put(iconUri, new WeakReference<>(icon));
        }
        holder.art.setImageBitmap(icon);
        String title = description.getTitle().toString();
        holder.title.setText(getCurrentPlayingId() == item.getQueueId() ? getBoldString(title) : title);
        holder.artist.setText(description.getSubtitle());

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mController != null) {
                    if (getCurrentPlayingId() == item.getQueueId()) {
                        mController.getTransportControls().play();
                    } else {
                        mController.getTransportControls().skipToQueueItem(item.getQueueId());
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                notifyDataSetChanged();
                            }
                        }, 2000);
                    }
                }
            }
        });
    }

    private long getCurrentPlayingId() {
        if (mController != null && mController.getPlaybackState() != null) {
            return mController.getPlaybackState().getActiveQueueItemId();
        } else return 0;
    }

    @Override
    public int getItemCount() {
        return mQueue.size();
    }

    private SpannableString getBoldString(String toBold) {
        SpannableString sp = new SpannableString(toBold);
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, sp.length(), 0);
        return sp;
    }

    public static class QueueItemHolder extends RecyclerView.ViewHolder {

        public ImageView art;
        public TextView title;
        public TextView artist;
        public TextView duration;

        public QueueItemHolder(View view) {
            super(view);
            art = (ImageView) itemView.findViewById(android.R.id.icon);
            title = (TextView) itemView.findViewById(android.R.id.title);
            artist = (TextView) itemView.findViewById(android.R.id.text1);
            duration = (TextView) itemView.findViewById(android.R.id.text2);
        }
    }
}