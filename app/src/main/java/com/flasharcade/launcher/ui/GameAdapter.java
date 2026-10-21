package com.flasharcade.launcher.ui;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.flasharcade.launcher.data.GameEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GameAdapter extends RecyclerView.Adapter<GameAdapter.GameHolder> {
    public interface Listener {
        void onLaunch(GameEntity game);
        void onOpenMenu(GameEntity game);
    }

    private final Listener listener;
    private final List<GameEntity> games = new ArrayList<>();
    private float density;

    public GameAdapter(Listener listener) {
        this.listener = listener;
        if (listener instanceof android.content.Context) {
            density = ((android.content.Context) listener).getResources().getDisplayMetrics().density;
        } else {
            density = 1f;
        }
    }

    public void setGames(List<GameEntity> values) {
        games.clear();
        games.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public GameHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        android.content.Context context = parent.getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(background(0xFF1A222B, 16));
        card.setClipToOutline(true);

        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(cover, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 112)));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 12));
        card.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 26)));

        TextView detail = new TextView(context);
        detail.setTextColor(0xFF91A0AD);
        detail.setTextSize(11);
        content.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 22)));

        Button play = new Button(context);
        play.setText("Play");
        play.setAllCaps(false);
        play.setTextColor(0xFF102019);
        play.setTextSize(13);
        play.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        play.setBackground(background(0xFF79E2A8, 10));
        content.addView(play, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 40)));

        return new GameHolder(card, cover, title, detail, play);
    }

    @Override
    public void onBindViewHolder(@NonNull GameHolder holder, int position) {
        GameEntity game = games.get(position);
        holder.title.setText(game.title);
        holder.detail.setText(game.launchCount + (game.launchCount == 1 ? " launch" : " launches") + "  ·  SWF");
        File coverFile = new File(game.coverPath);
        if (coverFile.isFile()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            holder.cover.setImageBitmap(BitmapFactory.decodeFile(coverFile.getAbsolutePath(), options));
        } else {
            holder.cover.setImageDrawable(background(0xFF26333F, 0));
        }
        holder.play.setOnClickListener(view -> listener.onLaunch(game));
        holder.itemView.setOnClickListener(view -> listener.onLaunch(game));
        holder.itemView.setOnLongClickListener(view -> {
            listener.onOpenMenu(game);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return games.size();
    }

    private static GradientDrawable background(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class GameHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView detail;
        final Button play;

        GameHolder(@NonNull View itemView, ImageView cover, TextView title, TextView detail, Button play) {
            super(itemView);
            this.cover = cover;
            this.title = title;
            this.detail = detail;
            this.play = play;
        }
    }
}
