package com.gathin.tvmusic;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

/** The library list: one focusable row per file, the track being played tinted teal. */
final class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.Holder> {
    interface Pick {
        void onPick(Track track);
    }

    private final List<Track> tracks;
    private final Pick pick;
    private String playingPath;

    TrackAdapter(List<Track> tracks, Pick pick) {
        this.tracks = tracks;
        this.pick = pick;
    }

    /** Marks the playing row, refreshing only the two rows that changed. */
    void setPlaying(String path) {
        String previous = playingPath;
        playingPath = path;
        notifyPathChanged(previous);
        notifyPathChanged(path);
    }

    String playingPath() {
        return playingPath;
    }

    private void notifyPathChanged(String path) {
        if (path == null) return;
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).path().equals(path)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_track, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        Track track = tracks.get(position);
        holder.index.setText(String.format(Locale.FRANCE, "%02d", position + 1));
        holder.title.setText(track.title);
        holder.artist.setText(track.artist != null ? track.artist
                : holder.itemView.getContext().getString(R.string.unknown_artist));
        holder.duration.setText(MusicActivity.clock(track.durationMs));
        holder.itemView.setActivated(track.path().equals(playingPath));
        holder.itemView.setOnClickListener(view -> pick.onPick(track));
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView index;
        final TextView title;
        final TextView artist;
        final TextView duration;

        Holder(View view) {
            super(view);
            index = view.findViewById(R.id.trackIndex);
            title = view.findViewById(R.id.trackTitle);
            artist = view.findViewById(R.id.trackArtist);
            duration = view.findViewById(R.id.trackDuration);
        }
    }
}
