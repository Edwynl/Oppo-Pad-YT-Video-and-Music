package com.edwyn.padtube

/** Page <-> app playback state for lock-screen controls. */
object MediaBridge {
    /** Name of the WebMessageListener object injected for YouTube origins only. */
    const val LISTENER_NAME = "padtubeMedia"

    /** Reports {"playing":bool,"title":string} whenever either changes. */
    val reportScript = """
        (() => {
          if (window !== window.top || window.__padTubeMedia || !window.$LISTENER_NAME) return;
          window.__padTubeMedia = true;
          let last = '';
          function report() {
            const v = document.querySelector('#movie_player video') || document.querySelector('video');
            // A muted video is a feed preview, not playback. Ads count even
            // though the cleaner mutes them: the watched video resumes after.
            const inAd = !!v && !!v.closest('.ad-showing, .ad-interrupting');
            const playing = !!v && !v.paused && !v.ended && (!v.muted || inAd);
            const title = (document.title || '')
              .replace(/^\(\d+\)\s*/, '')
              .replace(/\s*-\s*YouTube$/, '');
            const msg = JSON.stringify({ playing, title });
            if (msg !== last) {
              last = msg;
              $LISTENER_NAME.postMessage(msg);
            }
          }
          // Media events do not bubble, so listen in the capture phase.
          // timeupdate (~4/s) catches the page title changing after playback
          // starts; unchanged state is deduplicated above and never posted.
          ['play', 'playing', 'pause', 'ended', 'emptied', 'timeupdate', 'volumechange'].forEach(e =>
            document.addEventListener(e, report, true));
          document.addEventListener('yt-navigate-finish', report, true);
        })();
    """.trimIndent()

    fun commandScript(play: Boolean): String = """
        (() => {
          const p = document.querySelector('#movie_player');
          if (p && p.playVideo) { ${if (play) "p.playVideo()" else "p.pauseVideo()"}; return; }
          const v = document.querySelector('video');
          if (v) ${if (play) "v.play()" else "v.pause()"};
        })();
    """.trimIndent()
}
