package com.edwyn.padtube

object YouTubeCleaner {
    val script = """
        (() => {
          'use strict';
          const VERSION = '1.6.0';
          if (window.__padTubeCleaner === VERSION) {
            window.__padTubeCleanNow?.();
            return;
          }
          // Injected at document start; onPageFinished re-injects if the
          // document element did not exist yet.
          if (!document.documentElement) return;
          window.__padTubeCleaner = VERSION;

          // The mobile player treats a window much smaller than the screen
          // (Picture-in-Picture, split screen) as background playback and
          // pauses the video. Report a full-screen window so it keeps playing.
          try {
            Object.defineProperty(window, 'outerWidth', { get: () => screen.width, configurable: true });
            Object.defineProperty(window, 'outerHeight', { get: () => screen.height, configurable: true });
          } catch (_) {}

          const css = `
            ytd-display-ad-renderer,
            ytd-promoted-sparkles-web-renderer,
            ytd-promoted-video-renderer,
            ytd-ad-slot-renderer,
            ytd-in-feed-ad-layout-renderer,
            ytd-video-masthead-ad-v3-renderer,
            ytm-promoted-video-renderer,
            ytm-companion-ad-renderer,
            ytd-rich-item-renderer:has(ytd-ad-slot-renderer),
            ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"],
            #player-ads,
            #masthead-ad,
            .ytp-ad-overlay-container,
            .ytp-ad-player-overlay,
            .video-ads.ytp-iv-player-content,
            .ytp-ad-message-container,
            .ytp-ad-image-overlay { display: none !important; }

            /* Android tablets report a CSS viewport in dp. YouTube's mobile watch
               page otherwise stops around 600 CSS px and leaves half the screen
               blank in landscape. Keep the player full-width and its media
               contained so 16:9 and vertical videos are never stretched. */
            @media (orientation: landscape) and (min-width: 700px) {
              ytm-watch,
              ytm-watch-page,
              ytm-watch .player-container,
              ytm-watch #player,
              ytm-single-column-watch-next-results-renderer {
                width: 100% !important;
                max-width: none !important;
                margin-left: 0 !important;
                margin-right: 0 !important;
              }

              ytd-watch-flexy[theater] #player-theater-container,
              ytd-watch-flexy[theater] #player-container-outer,
              ytd-watch-flexy[theater] #player-container-inner {
                width: 100% !important;
                max-width: none !important;
              }
            }

            /* Make the desktop theater player span the full width in ANY
               orientation (especially portrait) so the video frame is as large
               as possible instead of a small letter-boxed rectangle. */
            ytd-watch-flexy[theater] #player-theater-container,
            ytd-watch-flexy[theater] #player-container-outer,
            ytd-watch-flexy[theater] #player-container-inner,
            ytd-watch-flexy[theater] #player-wide-container {
              width: 100% !important;
              max-width: none !important;
            }

            .html5-video-player video,
            .html5-video-player .video-stream {
              object-fit: contain !important;
            }

            /* ColorOS keeps the very top edge as a gesture/safety zone even when
               the status bar is hidden. Move YouTube's top controls into a
               dependable touch area without changing the video rectangle. */
            .html5-video-player.ytp-fullscreen .ytp-chrome-top {
              top: max(24px, env(safe-area-inset-top)) !important;
            }

            /* Picture-in-Picture entered from the toolbar (not fullscreen): the
               whole page is squeezed into the small window, so pin the player
               over the viewport and show only the video. Toggled by the app. */
            html.padtube-pip #movie_player {
              position: fixed !important;
              inset: 0 !important;
              width: 100vw !important;
              height: 100vh !important;
              z-index: 2147483647 !important;
              background: #000 !important;
            }
            html.padtube-pip,
            html.padtube-pip body { overflow: hidden !important; }
            html.padtube-pip #masthead-container,
            html.padtube-pip ytm-mobile-topbar-renderer,
            html.padtube-pip .mobile-topbar-header { display: none !important; }
          `;

          let style = document.getElementById('padtube-clean-style');
          if (!style) {
            style = document.createElement('style');
            style.id = 'padtube-clean-style';
            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);
          }

          let adMode = false;
          let savedRate = 1;
          let savedMuted = false;

          function visible(el) {
            if (!el) return false;
            const r = el.getBoundingClientRect();
            const s = getComputedStyle(el);
            return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
          }

          function removeAdUi() {
            document.querySelectorAll(
              'ytd-display-ad-renderer,ytd-promoted-video-renderer,ytd-ad-slot-renderer,' +
              'ytd-in-feed-ad-layout-renderer,ytm-promoted-video-renderer,#player-ads,#masthead-ad'
            // Remove the whole grid cell, not just the ad inside it, so the
            // home feed does not keep an empty card-sized hole.
            ).forEach(el => (el.closest('ytd-rich-item-renderer') || el).remove());

            document.querySelectorAll('ytd-enforcement-message-view-model').forEach(el => {
              const text = (el.textContent || '').toLowerCase();
              if (text.includes('ad blocker') || text.includes('广告拦截')) {
                (el.closest('tp-yt-paper-dialog') || el).remove();
                document.documentElement.style.removeProperty('overflow');
                document.body?.style.removeProperty('overflow');
              }
            });
          }

          function handlePlayerAd() {
            const player = document.querySelector('.html5-video-player, #movie_player');
            const video = document.querySelector('video');
            const showing = !!player && (
              player.classList.contains('ad-showing') ||
              player.classList.contains('ad-interrupting')
            );

            if (showing && video) {
              if (!adMode) {
                adMode = true;
                savedRate = video.playbackRate || 1;
                savedMuted = video.muted;
              }
              video.muted = true;
              try { video.playbackRate = 16; } catch (_) {}
              if (Number.isFinite(video.duration) && video.duration > 0) {
                try { video.currentTime = Math.max(0, video.duration - 0.08); } catch (_) {}
              }
              // Click any skip control inside the player. Modern YouTube ad
              // skip buttons can be <div role="button"> (not <button>) and only
              // carry text / aria-label, so match broadly — but stay inside the
              // player to avoid unrelated "skip navigation" buttons.
              (player || document).querySelectorAll('button,[role="button"],a').forEach(el => {
                const cls = String(el.className || '').toLowerCase();
                const al = (el.getAttribute('aria-label') || '').toLowerCase();
                const txt = (el.textContent || '').trim();
                if ((cls.includes('skip') || al.includes('skip') ||
                     al.includes('跳过') || (txt.includes('跳过') && txt.length < 10)) &&
                    visible(el)) {
                  try { el.click(); } catch (_) {}
                }
              });
            } else if (adMode && video) {
              adMode = false;
              try { video.playbackRate = savedRate; } catch (_) {}
              video.muted = savedMuted;
              video.play().catch(() => {});
            }
          }

          // The mobile site (default on phones) starts every video muted and
          // shows a "tap to unmute" popup, assuming the browser blocks audible
          // autoplay. PadTube's WebView allows it, so unmute while that popup
          // is visible; a mute the user chose does not show it.
          function autoUnmute() {
            if (adMode || !visible(document.querySelector('.ytp-unmute'))) return;
            try { document.querySelector('#movie_player')?.unMute?.(); } catch (_) {}
          }

          function cleanNow() {
            removeAdUi();
            handlePlayerAd();
            autoUnmute();
            applyLandscapePlayerLayout();
          }

          let lastTheaterPath = '';

          function applyLandscapePlayerLayout() {
            // Enlarge the desktop watch player by switching to theater mode in
            // any orientation (portrait included) so the video fills the width.
            if (!location.pathname.startsWith('/watch')) return;

            const flexy = document.querySelector('ytd-watch-flexy');
            if (!flexy || flexy.hasAttribute('theater') || document.fullscreenElement) return;
            if (lastTheaterPath === location.pathname) return;

            const sizeButton = document.querySelector('.ytp-size-button');
            if (visible(sizeButton)) {
              lastTheaterPath = location.pathname;
              sizeButton.click();
            }
          }

          // YouTube mutates the DOM hundreds of times per second (progress bar,
          // hover states). Coalesce observer bursts into one pass per ~100ms
          // instead of re-scanning the whole document on every mutation.
          let cleanQueued = false;
          function scheduleClean() {
            if (cleanQueued) return;
            cleanQueued = true;
            setTimeout(() => {
              cleanQueued = false;
              cleanNow();
            }, 100);
          }

          window.__padTubeCleanNow = cleanNow;
          new MutationObserver(scheduleClean).observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class']
          });
          setInterval(cleanNow, 500);
          document.addEventListener('yt-navigate-finish', cleanNow, true);
          cleanNow();
        })();
    """.trimIndent()
}
