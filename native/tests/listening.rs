use serde_json::{json, Value};
use ytm_core::dispatch;

fn parsed(data: Value) -> Value {
    dispatch(json!({"op":"parse","data":data})).unwrap()
}

fn nav(id: &str, kind: &str) -> Value {
    json!({"browseEndpoint":{"browseId":id,"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":kind}}}})
}

fn row(id: &str) -> Value {
    json!({"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":id,"playlistSetVideoId":"set-123"},
        "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                {"text":"Artist","navigationEndpoint":nav("UC_artist","MUSIC_PAGE_TYPE_ARTIST")},
                {"text":" • "},
                {"text":"Album","navigationEndpoint":nav("MPRE_album","MUSIC_PAGE_TYPE_ALBUM")},
                {"text":" • 3:17"}
            ]}}}
        ],
        "badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}],
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
            {"url":"https://lh3.googleusercontent.com/large=w600-h600-l90-rj","width":600,"height":600},
            {"url":"https://lh3.googleusercontent.com/small=w60-h60-l90-rj","width":60,"height":60}
        ]}}}
    }})
}

#[test]
fn cards_keep_collection_kinds_even_when_they_have_play_overlays() {
    let data = [
        ("MPRE_album", "MUSIC_PAGE_TYPE_ALBUM", "album"),
        ("UC_artist", "MUSIC_PAGE_TYPE_ARTIST", "artist"),
        ("VLPL_list", "MUSIC_PAGE_TYPE_PLAYLIST", "playlist"),
        ("MPSP_show", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE", "show"),
    ].into_iter().map(|(id, kind, _)| json!({"musicTwoRowItemRenderer":{
        "title":{"runs":[{"text":id}]},"navigationEndpoint":nav(id,kind),
        "overlay":{"musicItemThumbnailOverlayRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"overlay0001"}}}}
    }})).collect::<Vec<_>>();
    let result = parsed(json!(data));
    let kinds = result["items"].as_array().unwrap().iter().map(|i| i["kind"].as_str().unwrap()).collect::<Vec<_>>();
    assert_eq!(kinds, vec!["album", "artist", "playlist", "show"]);
}

#[test]
fn row_metadata_preserves_explicit_duration_links_and_large_art() {
    let result = parsed(row("abcdefghijk"));
    let item = &result["items"][0];
    assert_eq!(item["explicit"], true);
    assert_eq!(item["durationMs"], 197_000);
    assert_eq!(item["artistId"], "UC_artist");
    assert_eq!(item["albumId"], "MPRE_album");
    assert_eq!(item["artistTitle"], "Artist");
    assert_eq!(item["albumTitle"], "Album");
    assert_eq!(item["setVideoId"], "set-123");
    assert_eq!(item["art"], "https://lh3.googleusercontent.com/large=w1200-h1200-l90-rj");
}

#[test]
fn playlist_entries_keep_repeated_songs_with_different_entry_ids() {
    let a = row("abcdefghijk");
    let mut b = a.clone();
    b["musicResponsiveListItemRenderer"]["playlistItemData"]["playlistSetVideoId"] = json!("set-456");
    assert_eq!(parsed(json!([a,b]))["items"].as_array().unwrap().len(), 2);
}

#[test]
fn foreign_artwork_urls_are_not_rewritten() {
    let result = parsed(json!({"musicTwoRowItemRenderer":{
        "title":{"simpleText":"Album"},"navigationEndpoint":nav("MPRE_album","MUSIC_PAGE_TYPE_ALBUM"),
        "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.org/cover=w60-h60","width":60,"height":60}]}}}
    }}));
    assert_eq!(result["items"][0]["art"],"https://example.org/cover=w60-h60");
}

#[test]
fn radio_playlist_panel_is_playable_and_deduplicated() {
    let track = json!({"playlistPanelVideoRenderer":{
        "videoId":"radio000001","title":{"simpleText":"Radio song"},
        "longBylineText":{"runs":[{"text":"Radio artist"}]},"lengthText":{"simpleText":"1:02:03"},
        "navigationEndpoint":{"watchEndpoint":{"videoId":"radio000001","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}}}
    }});
    let result = parsed(json!({"playlistPanelRenderer":{"contents":[track.clone(),{"playlistPanelVideoWrapperRenderer":{"primaryRenderer":track}}]}}));
    assert_eq!(result["items"].as_array().unwrap().len(),1);
    assert_eq!(result["items"][0]["kind"],"song");
    assert_eq!(result["items"][0]["durationMs"],3_723_000);
    assert_eq!(result["items"][0]["musicVideo"],true);
}

#[test]
fn home_sections_mood_filters_and_continuation_survive_parsing() {
    let result = parsed(json!({"contents":[
        {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Quick picks"}]}}},"contents":[row("abcdefghijk")]}},
        {"chipCloudChipRenderer":{"text":{"simpleText":"Relax"},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home","params":"mood-relax"}}}},
        {"musicNavigationButtonRenderer":{"buttonText":{"simpleText":"Jazz"},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"genre-jazz"}}}},
        {"nextContinuationData":{"continuation":"page-two"}}
    ]}));
    assert_eq!(result["sections"][0]["title"],"Quick picks");
    assert_eq!(result["sections"][0]["items"][0]["id"],"abcdefghijk");
    assert_eq!(result["chips"][0]["params"],"mood-relax");
    assert_eq!(result["chips"][1]["browseId"],"FEmusic_moods_and_genres_category");
    assert_eq!(result["chips"][1]["params"],"genre-jazz");
    assert_eq!(result["continuation"],"page-two");
}

#[test]
fn signed_web_player_uses_account_origin_and_restricted_context() {
    let request = dispatch(json!({"op":"request","endpoint":"player","body":{"videoId":"abcdefghijk"},
        "webPlayer":true,"version":"live-version","cookie":"SAPISID=secret; __Secure-3PAPISID=other", "timestamp":1_700_000_000,
        "restricted":true,"sessionIndex":2})).unwrap();
    assert_eq!(request["body"]["context"]["client"]["clientName"],"WEB_REMIX");
    assert_eq!(request["body"]["context"]["user"]["enableSafetyMode"],true);
    assert_eq!(request["headers"]["Origin"],"https://music.youtube.com");
    assert_eq!(request["headers"]["X-Goog-AuthUser"],"2");
    let auth = request["headers"]["Authorization"].as_str().unwrap();
    assert!(auth.starts_with("SAPISIDHASH 1700000000_"));
    assert!(auth.contains(" SAPISID3PHASH 1700000000_"));
    assert!(!auth.contains("secret"));
    assert_eq!(request["body"]["contentCheckOk"],true);
}

#[test]
fn authenticated_actions_are_bounded_and_header_injection_is_rejected() {
    for endpoint in ["like/like","like/removelike","playlist/create","playlist/delete","browse/edit_playlist","subscription/subscribe","subscription/unsubscribe"] {
        assert!(dispatch(json!({"op":"request","endpoint":endpoint,"body":{}})).is_ok(),"{endpoint}");
    }
    assert!(dispatch(json!({"op":"request","endpoint":"../../outside","body":{}})).is_err());
    assert!(dispatch(json!({"op":"request","endpoint":"browse","body":{},"cookie":"SAPISID=x\r\nInjected: yes"})).is_err());
}

fn player_data() -> Value {
    json!({"playabilityStatus":{"status":"OK"},"videoDetails":{"lengthSeconds":"200"},
        "playerConfig":{"audioConfig":{"trackAbsoluteLoudnessLkfs":-10.0,"loudnessTargetLkfs":-14.0}},
        "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/master.m3u8","adaptiveFormats":[
            {"itag":139,"mimeType":"audio/mp4; codecs=\"mp4a.40.5\"","url":"https://audio.googlevideo.com/aac-low","averageBitrate":48000,"contentLength":"1200000"},
            {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","url":"https://audio.googlevideo.com/aac","averageBitrate":128000,"contentLength":"3200000","approxDurationMs":"200000"},
            {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://audio.googlevideo.com/opus","averageBitrate":160000,"contentLength":"4000000"},
            {"itag":774,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://audio.googlevideo.com/premium-opus","averageBitrate":256000,"contentLength":"6400000"},
            {"itag":137,"mimeType":"video/mp4","url":"https://audio.googlevideo.com/video-only","averageBitrate":6000000}
        ]}})
}

#[test]
fn playback_prefers_hls_and_downloads_choose_highest_aac_with_metadata() {
    let live = dispatch(json!({"op":"stream","data":player_data()})).unwrap();
    assert_eq!(live["hls"],true);
    let saved = dispatch(json!({"op":"stream","data":player_data(),"preferProgressive":true,"preferAac":true})).unwrap();
    assert_eq!(saved["itag"],140);
    assert_eq!(saved["hls"],false);
    assert_eq!(saved["size"],3_200_000);
    assert_eq!(saved["bitrate"],128_000);
    assert_eq!(saved["durationMs"],200_000);
    assert_eq!(saved["loudnessDb"],4.0);
}

#[test]
fn best_opus_does_not_select_a_video_only_format() {
    let result = dispatch(json!({"op":"stream","data":player_data(),"preferProgressive":true,"preferred":"opus"})).unwrap();
    assert_eq!(result["itag"],774);
    assert_eq!(result["bitrate"],256_000);
    assert!(result["mime"].as_str().unwrap().starts_with("audio/"));
}

#[test]
fn video_requires_an_audio_video_hls_manifest() {
    let result = dispatch(json!({"op":"stream","data":player_data(),"video":true})).unwrap();
    assert_eq!(result["url"],"https://manifest.googlevideo.com/master.m3u8");
    assert_eq!(result["mime"],"application/x-mpegURL");
    let mut missing = player_data();
    missing["streamingData"].as_object_mut().unwrap().remove("hlsManifestUrl");
    assert!(dispatch(json!({"op":"stream","data":missing,"video":true})).is_err());
}

#[test]
fn invalid_tag_paths_fail_without_creating_an_audio_file() {
    assert!(dispatch(json!({"op":"tag_m4a","path":"relative-missing.m4a","title":"Title"})).is_err());
    assert!(dispatch(json!({"op":"tag_m4a","title":"Title"})).is_err());
}
