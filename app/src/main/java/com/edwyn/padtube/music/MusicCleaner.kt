package com.edwyn.padtube.music

object MusicCleaner {
    /** WebMessageListener injected for music.youtube.com only. */
    const val BRIDGE_NAME = "PadMusicBridge"

    val script = """
        (() => {
          'use strict';
          // Document-start injection also reaches same-origin iframes, which
          // have no player and would report a false "not playing" state.
          if (window !== window.top) return;
          const VERSION = '1.2.0';
          if (window.__padMusicVersion === VERSION) {
            window.__padMusicCleanNow?.();
            return;
          }
          // Injected at document start; onPageFinished re-injects if the
          // document element did not exist yet.
          if (!document.documentElement) return;
          window.__padMusicVersion = VERSION;

          const AD_FIELDS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
          function prune(value) {
            if (!value || typeof value !== 'object') return;
            AD_FIELDS.forEach(key => { if (key in value) delete value[key]; });
            if (Array.isArray(value)) value.forEach(prune);
            else Object.keys(value).forEach(key => {
              const child = value[key];
              if (child && typeof child === 'object') prune(child);
            });
          }

          if (!window.__padMusicFetchPatched && typeof window.fetch === 'function') {
            window.__padMusicFetchPatched = true;
            const originalFetch = window.fetch;
            window.fetch = async function(input, init) {
              const response = await originalFetch.call(this, input, init);
              const url = typeof input === 'string' ? input : (input && input.url) || '';
              const path = String(url).split('#')[0].split('?')[0];
              if (!path.endsWith('/youtubei/v1/player') && !path.endsWith('/youtubei/v1/get_watch')) {
                return response;
              }
              try {
                const data = await response.clone().json();
                prune(data);
                const headers = new Headers(response.headers);
                headers.delete('content-length');
                headers.delete('content-encoding');
                return new Response(JSON.stringify(data), {
                  status: response.status,
                  statusText: response.statusText,
                  headers: headers
                });
              } catch (_) {
                return response;
              }
            };
          }

          const css = `
            ytmusic-ad-instream-ads-renderer,
            ytmusic-mealbar-promo-renderer,
            ytmusic-statement-banner-renderer,
            ytmusic-you-there-renderer,
            .ytmusic-player-ad-overlay,
            .ytp-ad-overlay-container,
            .ytp-ad-player-overlay,
            .video-ads.ytp-iv-player-content,
            #player-ads,
            #masthead-ad,
            ytd-display-ad-renderer,
            ytd-promoted-video-renderer,
            ytd-ad-slot-renderer { display:none!important;visibility:hidden!important;opacity:0!important; }

            ytmusic-player#player[player-ui-state="MINIPLAYER"] {
              position: fixed!important;
              top: 16px!important;
              right: 16px!important;
              left: auto!important;
              bottom: auto!important;
              width: min(38vw, 560px)!important;
              height: auto!important;
              aspect-ratio: 16 / 9!important;
              transform: none!important;
              overflow: hidden!important;
              background: #000!important;
              z-index: 2147483000!important;
            }
            ytmusic-player#player[player-ui-state="MINIPLAYER"] video {
              width: 100%!important;
              height: 100%!important;
              object-fit: contain!important;
            }

            ytmusic-app-layout[player-page-open_] ytmusic-player-page #main-panel {
              min-width: 0!important;
              display: flex!important;
              align-items: center!important;
              justify-content: center!important;
              overflow: visible!important;
            }
            ytmusic-app-layout[player-page-open_] ytmusic-player#player {
              position: relative!important;
              inset: auto!important;
              width: min(76vw, 1180px)!important;
              max-width: calc(100vw - 40px)!important;
              height: auto!important;
              aspect-ratio: 16 / 9!important;
              margin: 16px auto!important;
              transform: none!important;
              overflow: hidden!important;
              background: #000!important;
            }
            ytmusic-app-layout[player-page-open_] ytmusic-player#player video {
              width: 100%!important;
              height: 100%!important;
              object-fit: contain!important;
            }
          `;
          let style = document.getElementById('padmusic-clean-style');
          if (!style) {
            style = document.createElement('style');
            style.id = 'padmusic-clean-style';
            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);
          }

          let video = null;
          let intendedPlaying = false;
          let explicitPause = false;
          let adMode = false;
          let savedRate = 1;
          let savedMuted = false;
          let lastState = '';
          let lastPositionAt = 0;

          function getPlayer() {
            return document.querySelector('#movie_player, .html5-video-player');
          }

          function getVideo() {
            const current = document.querySelector('video');
            if (current && current !== video) {
              video = current;
              video.setAttribute('playsinline', '');
              video.addEventListener('play', () => {
                intendedPlaying = true;
                explicitPause = false;
                notifyAndroid();
              }, { passive: true });
              video.addEventListener('playing', () => {
                intendedPlaying = true;
                explicitPause = false;
                notifyAndroid();
              }, { passive: true });
              video.addEventListener('pause', () => {
                if (document.hidden && intendedPlaying && !explicitPause && !adMode) {
                  setTimeout(resumeInBackground, 120);
                } else if (!document.hidden && !adMode) {
                  // Paused on the visible page: the user's choice, or the track
                  // ended. Going to the background must not undo it.
                  intendedPlaying = false;
                }
                setTimeout(notifyAndroid, 150);
              }, { passive: true });
              video.addEventListener('ended', notifyAndroid, { passive: true });
              video.addEventListener('timeupdate', () => {
                if (Date.now() - lastPositionAt >= 1000) notifyAndroid();
              }, { passive: true });
            }
            return video;
          }

          function textOf(selectors) {
            for (const selector of selectors) {
              const el = document.querySelector(selector);
              const text = (el && (el.getAttribute('title') || el.textContent) || '').trim();
              if (text) return text;
            }
            return '';
          }

          function artworkOf() {
            const bar = document.querySelector('ytmusic-player-bar');
            const candidates = [];
            if (bar) candidates.push(...bar.querySelectorAll('img'));
            candidates.push(...document.querySelectorAll(
              'ytmusic-player #song-image img, .content-info-wrapper img, #thumbnail img'
            ));
            for (const img of candidates) {
              const src = img && (img.src || img.getAttribute('src'));
              if (src && src.startsWith('http') && !src.startsWith('data:')) return src;
            }
            return '';
          }

          function nowPlaying() {
            return {
              title: textOf([
                'ytmusic-player-bar .title',
                'ytmusic-player-bar yt-formatted-string.title',
                '.content-info-wrapper .title'
              ]) || document.title.replace(/\s*-\s*YouTube Music\s*$/i, ''),
              artist: textOf([
                'ytmusic-player-bar .byline',
                'ytmusic-player-bar .subtitle',
                '.content-info-wrapper .subtitle'
              ]) || 'YouTube Music',
              artwork: artworkOf()
            };
          }

          function notifyAndroid() {
            const media = getVideo();
            const meta = nowPlaying();
            const playing = !!media && !media.paused && !media.ended;
            const position = media && isFinite(media.currentTime) ? media.currentTime : 0;
            const duration = media && isFinite(media.duration) ? media.duration : 0;
            const signature = meta.title + '|' + meta.artist + '|' + meta.artwork + '|' + playing;
            const now = Date.now();
            const metaChanged = signature !== lastState;
            const positionDue = playing && (now - lastPositionAt) >= 1000;
            if (!metaChanged && !positionDue) return;
            lastState = signature;
            lastPositionAt = now;
            try {
              window.$BRIDGE_NAME?.postMessage(JSON.stringify({
                title: meta.title, artist: meta.artist, artwork: meta.artwork,
                playing, position, duration
              }));
            } catch (_) {}
          }

          function isAdShowing() {
            const player = getPlayer();
            return !!player && (
              player.classList.contains('ad-showing') ||
              player.classList.contains('ad-interrupting')
            );
          }

          function handleAds() {
            const media = getVideo();
            const showing = isAdShowing();
            if (showing && media) {
              if (!adMode) {
                adMode = true;
                savedRate = media.playbackRate || 1;
                savedMuted = media.muted;
              }
              media.muted = true;
              try { media.playbackRate = 16; } catch (_) {}
              if (Number.isFinite(media.duration) && media.duration > 0) {
                try { media.currentTime = Math.max(0, media.duration - 0.08); } catch (_) {}
              }
              document.querySelectorAll(
                '.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,button[class*="skip"]'
              ).forEach(button => { try { button.click(); } catch (_) {} });
            } else if (adMode && media) {
              adMode = false;
              try { media.playbackRate = savedRate; } catch (_) {}
              media.muted = savedMuted;
              if (intendedPlaying) media.play().catch(() => {});
            }
          }

          function removeAdUi() {
            document.querySelectorAll(
              'ytmusic-ad-instream-ads-renderer,ytmusic-mealbar-promo-renderer,' +
              'ytmusic-statement-banner-renderer,.ytmusic-player-ad-overlay,#player-ads'
            ).forEach(el => el.remove());
            document.querySelectorAll('ytmusic-you-there-renderer').forEach(el => {
              const button = el.querySelector('button');
              if (button) button.click();
              el.remove();
            });
          }

          function resumeInBackground() {
            const media = getVideo();
            if (!media || explicitPause || !intendedPlaying || adMode || !media.paused || media.ended) return;
            media.play().catch(() => {});
          }

          document.addEventListener('visibilitychange', () => {
            if (document.hidden && intendedPlaying && !explicitPause) {
              setTimeout(resumeInBackground, 80);
              setTimeout(resumeInBackground, 500);
              setTimeout(resumeInBackground, 1600);
            }
          }, true);

          window.__padMusicCommand = function(command, arg) {
            const media = getVideo();
            const player = getPlayer();
            if (command === 'seek') {
              const seconds = Number(arg) / 1000;
              if (media && isFinite(seconds)) {
                try { media.currentTime = Math.max(0, seconds); } catch (_) {}
                if (player && typeof player.seekTo === 'function') {
                  try { player.seekTo(Math.max(0, seconds), true); } catch (_) {}
                }
              }
              setTimeout(notifyAndroid, 120);
              return;
            }
            if (command === 'play') {
              intendedPlaying = true;
              explicitPause = false;
              if (player && typeof player.playVideo === 'function') player.playVideo();
              if (media) media.play().catch(() => {});
            } else if (command === 'pause') {
              intendedPlaying = false;
              explicitPause = true;
              if (player && typeof player.pauseVideo === 'function') player.pauseVideo();
              if (media) media.pause();
            } else if (command === 'next') {
              intendedPlaying = true;
              explicitPause = false;
              if (player && typeof player.nextVideo === 'function') player.nextVideo();
              else document.querySelector('ytmusic-player-bar .next-button, .ytp-next-button')?.click();
            } else if (command === 'previous') {
              intendedPlaying = true;
              explicitPause = false;
              if (player && typeof player.previousVideo === 'function') player.previousVideo();
              else document.querySelector('ytmusic-player-bar .previous-button')?.click();
            } else if (command === 'video') {
              const target = player || media;
              if (document.fullscreenElement) {
                document.exitFullscreen?.();
              } else if (target) {
                const request = target.requestFullscreen || target.webkitRequestFullscreen;
                if (request) {
                  try {
                    const result = request.call(target);
                    result?.catch?.(() => {});
                  } catch (_) {}
                }
              }
            }
            setTimeout(notifyAndroid, 200);
          };

          function cleanNow() {
            watchPlayer();
            getVideo();
            removeAdUi();
            handleAds();
            if (document.hidden) resumeInBackground();
            notifyAndroid();
          }

          // YouTube Music mutates the DOM many times per second (progress bar,
          // lyrics); coalesce bursts into one pass per ~100ms instead of
          // re-scanning the document on every mutation.
          let cleanQueued = false;
          function scheduleClean() {
            if (cleanQueued) return;
            cleanQueued = true;
            setTimeout(() => {
              cleanQueued = false;
              cleanNow();
            }, 100);
          }

          window.__padMusicCleanNow = cleanNow;

          // New elements anywhere (ad renderers, "are you there" dialogs) or
          // class changes inside the player (ad-showing) trigger a pass; class
          // changes across the rest of the page do not. Position updates come
          // from the video's timeupdate event.
          new MutationObserver(scheduleClean).observe(document.documentElement, {
            childList: true,
            subtree: true
          });
          const playerObserver = new MutationObserver(scheduleClean);
          let watchedPlayer = null;
          function watchPlayer() {
            const player = getPlayer();
            if (!player || player === watchedPlayer) return;
            watchedPlayer = player;
            playerObserver.disconnect();
            playerObserver.observe(player, {
              attributes: true,
              attributeFilter: ['class'],
              subtree: true
            });
          }

          // Safety net for anything the observers miss: about every 3s, or
          // every tick while an ad is being skipped.
          let ticks = 0;
          setInterval(() => {
            if (adMode || ++ticks % 5 === 0) cleanNow();
          }, 650);
          document.addEventListener('yt-navigate-finish', cleanNow, true);
          cleanNow();
        })();
    """.trimIndent()
}
