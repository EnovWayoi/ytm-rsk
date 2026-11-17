use serde_json::{json, Value};
use std::collections::HashSet;
use sha1::{Digest, Sha1};

pub const PLAYER_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
pub const WEB_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

fn text(v: &Value) -> String {
    v["simpleText"].as_str().map(str::to_owned).unwrap_or_else(|| {
        v["runs"].as_array().map(|r| r.iter().filter_map(|r| r["text"].as_str()).collect()).unwrap_or_default()
    })
}

fn find_string<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    match v {
        Value::Object(m) => m.get(key).and_then(Value::as_str).or_else(|| m.values().find_map(|v| find_string(v, key))),
        Value::Array(a) => a.iter().find_map(|v| find_string(v, key)),
        _ => None,
    }
}

fn artwork_url(url: &str) -> String {
    let host = url.strip_prefix("https://").and_then(|s| s.split('/').next()).unwrap_or("");
    if !(host == "googleusercontent.com" || host.ends_with(".googleusercontent.com")) { return url.to_owned(); }
    let Some((base, size)) = url.rsplit_once('=') else { return url.to_owned(); };
    let mut changed = false;
    let size = size.split('-').map(|part| {
        if part.len() > 1 && (part.starts_with('w') || part.starts_with('h')) && part[1..].bytes().all(|b| b.is_ascii_digit()) {
            changed = true;
            format!("{}1200", &part[..1])
        } else { part.to_owned() }
    }).collect::<Vec<_>>().join("-");
    if changed { format!("{base}={size}") } else { url.to_owned() }
}

fn best_thumbnail(v: &Value) -> Option<(&str, u64)> {
    match v {
        Value::Object(m) => {
            if let Some(a) = m.get("thumbnails").and_then(Value::as_array) {
                return a.iter().filter_map(|t| Some((t["url"].as_str()?, t["width"].as_u64().unwrap_or(0).saturating_mul(t["height"].as_u64().unwrap_or(1)))))
                    .max_by_key(|(_, area)| *area);
            }
            m.values().filter_map(best_thumbnail).max_by_key(|(_, area)| *area)
        }
        Value::Array(a) => a.iter().filter_map(best_thumbnail).max_by_key(|(_, area)| *area),
        _ => None,
    }
}

fn thumbnail(v: &Value) -> String {
    let target = [&v["thumbnail"], &v["thumbnailRenderer"], &v["foregroundThumbnail"]].into_iter()
        .filter_map(best_thumbnail).max_by_key(|(_, area)| *area).or_else(|| best_thumbnail(v));
    target.map(|(url, _)| artwork_url(url)).unwrap_or_default()
}

fn collection_kind(id: &str, page_type: &str) -> &'static str {
    match page_type {
        "MUSIC_PAGE_TYPE_ALBUM" | "MUSIC_PAGE_TYPE_AUDIOBOOK" => "album",
        "MUSIC_PAGE_TYPE_ARTIST" | "MUSIC_PAGE_TYPE_USER_CHANNEL" | "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" => "artist",
        "MUSIC_PAGE_TYPE_PLAYLIST" => "playlist",
        "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE" => "show",
        _ if id.starts_with("MPRE") => "album",
        _ if id.starts_with("UC") || id.starts_with("MPLA") => "artist",
        _ if id.starts_with("MPSP") => "show",
        _ if id.starts_with("VL") || id.starts_with("PL") || id.starts_with("RD") || id.starts_with("OLAK") => "playlist",
        _ => "collection",
    }
}

fn link_id<'a>(v: &'a Value, kind: &str) -> Option<&'a str> {
    match v {
        Value::Object(m) => {
            if let Some(id) = m.get("browseId").and_then(Value::as_str) {
                let page_type = find_string(v, "pageType").unwrap_or("");
                if collection_kind(id, page_type) == kind { return Some(id); }
            }
            m.values().find_map(|v| link_id(v, kind))
        }
        Value::Array(a) => a.iter().find_map(|v| link_id(v, kind)),
        _ => None,
    }
}

fn duration_ms(s: &str) -> Option<u64> {
    if !s.contains(':') { return None; }
    s.trim().split(':').try_fold(0u64, |acc, p| {
        if p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()) { return None; }
        acc.checked_mul(60)?.checked_add(p.parse::<u64>().ok()?)
    })?.checked_mul(1000)
}

fn link_title(v: &Value, kind: &str) -> Option<String> {
    match v {
        Value::Object(m) => {
            let nav = &v["navigationEndpoint"]["browseEndpoint"];
            if let Some(id) = nav["browseId"].as_str() {
                if collection_kind(id, find_string(nav, "pageType").unwrap_or("")) == kind {
                    if let Some(label) = v["text"].as_str().filter(|s| !s.is_empty()) { return Some(label.to_owned()); }
                }
            }
            m.values().find_map(|v| link_title(v, kind))
        }
        Value::Array(a) => a.iter().find_map(|v| link_title(v, kind)),
        _ => None,
    }
}

fn row_duration(r: &Value, subtitle: &str) -> u64 {
    let fixed = text(&r["fixedColumns"][0]["musicResponsiveListItemFixedColumnRenderer"]["text"]);
    duration_ms(&text(&r["lengthText"])).or_else(|| duration_ms(&fixed))
        .or_else(|| subtitle.split(['•', '·']).find_map(duration_ms)).unwrap_or(0)
}

fn contains_string(v: &Value, key: &str, wanted: &str) -> bool {
    match v {
        Value::Object(m) => m.get(key).and_then(Value::as_str) == Some(wanted) || m.values().any(|v| contains_string(v, key, wanted)),
        Value::Array(a) => a.iter().any(|v| contains_string(v, key, wanted)),
        _ => false,
    }
}

fn item(r: &Value) -> Option<Value> {
    let flex = &r["flexColumns"];
    let title = if flex.is_array() { text(&flex[0]["musicResponsiveListItemFlexColumnRenderer"]["text"]) } else { text(&r["title"]) };
    if title.is_empty() { return None; }
    let video = r["videoId"].as_str().or(r["playlistItemData"]["videoId"].as_str())
        .or_else(|| r["navigationEndpoint"]["watchEndpoint"]["videoId"].as_str())
        .or_else(|| r["onTap"]["watchEndpoint"]["videoId"].as_str())
        .or_else(|| find_string(&r["overlay"], "videoId"))
        .or_else(|| find_string(&flex[0], "videoId"));
    let browse = r["navigationEndpoint"]["browseEndpoint"]["browseId"].as_str()
        .or_else(|| find_string(&flex[0], "browseId"));
    let playlist = r["navigationEndpoint"]["watchPlaylistEndpoint"]["playlistId"].as_str();
    let primary_browse = r["navigationEndpoint"]["browseEndpoint"]["browseId"].as_str();
    let video_type = find_string(&r["navigationEndpoint"], "musicVideoType")
        .or_else(|| find_string(&r["overlay"], "musicVideoType"))
        .or_else(|| find_string(&flex[0], "musicVideoType")).unwrap_or("");
    let (id, kind) = if let Some(id) = primary_browse {
        (id, collection_kind(id, find_string(&r["navigationEndpoint"], "pageType").unwrap_or("")))
    } else if let Some(id) = video { (id, if video_type == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" { "episode" } else { "song" }) }
    else if let Some(id) = playlist { (id, "playlist") }
    else { let id = browse?; (id, collection_kind(id, find_string(&flex[0], "pageType").unwrap_or(""))) };
    let subtitle = if flex.is_array() { text(&flex[1]["musicResponsiveListItemFlexColumnRenderer"]["text"]) }
        else if !r["longBylineText"].is_null() { text(&r["longBylineText"]) }
        else if !r["subtitle"].is_null() { text(&r["subtitle"]) } else { text(&r["secondTitle"]) };
    let links = json!([r["flexColumns"],r["subtitle"],r["longBylineText"],r["secondTitle"]]);
    let explicit = contains_string(&r["badges"], "iconType", "MUSIC_EXPLICIT_BADGE")
        || contains_string(&r["subtitleBadges"], "iconType", "MUSIC_EXPLICIT_BADGE");
    Some(json!({"id":id,"kind":kind,"title":title,"subtitle":subtitle,"art":thumbnail(r),
        "explicit":explicit,"durationMs":row_duration(r,&subtitle),"artistId":link_id(&links,"artist").unwrap_or(""),
        "albumId":link_id(&links,"album").unwrap_or(""),"playlistId":playlist.or_else(|| find_string(&r["overlay"],"playlistId")).unwrap_or(""),
        "artistTitle":link_title(&links,"artist").unwrap_or_default(),"albumTitle":link_title(&links,"album").unwrap_or_default(),
        "setVideoId":r["playlistItemData"]["playlistSetVideoId"].as_str().or_else(|| find_string(r,"setVideoId")).unwrap_or(""),
        "musicVideo":video_type == "MUSIC_VIDEO_TYPE_OMV"}))
}

fn collect(v: &Value, out: &mut Vec<Value>, seen: &mut HashSet<String>, depth: usize) {
    if depth > 80 { return; }
    match v {
        Value::Object(m) => {
            for key in ["musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "playlistPanelVideoRenderer", "musicMultiRowListItemRenderer"] {
                if let Some(i) = m.get(key).and_then(item) {
                    let key = format!("{}:{}", i["id"].as_str().unwrap_or_default(), i["setVideoId"].as_str().unwrap_or_default());
                    if seen.insert(key) { out.push(i); }
                    return;
                }
            }
            for child in m.values() { collect(child, out, seen, depth + 1); }
        }
        Value::Array(a) => for child in a { collect(child, out, seen, depth + 1); },
        _ => (),
    }
}

fn sections(v: &Value, out: &mut Vec<Value>, depth: usize) {
    if depth > 80 { return; }
    match v {
        Value::Object(m) => {
            for key in ["musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer", "gridRenderer"] {
                if let Some(shelf) = m.get(key) {
                    let title = [text(&shelf["header"]["musicCarouselShelfBasicHeaderRenderer"]["title"]),text(&shelf["title"]),text(&shelf["header"]["gridHeaderRenderer"]["title"])].into_iter().find(|s| !s.is_empty()).unwrap_or_default();
                    let mut tracks = Vec::new();
                    collect(shelf, &mut tracks, &mut HashSet::new(), 0);
                    if !title.is_empty() && !tracks.is_empty() { out.push(json!({"title":title,"items":tracks})); }
                    return;
                }
            }
            for child in m.values() { sections(child, out, depth + 1); }
        }
        Value::Array(a) => for child in a { sections(child, out, depth + 1); },
        _ => (),
    }
}

fn chips(v: &Value, out: &mut Vec<Value>, depth: usize) {
    if depth > 80 { return; }
    match v {
        Value::Object(m) => {
            if let Some(r) = m.get("chipCloudChipRenderer") {
                let title = text(&r["text"]);
                let nav = if r["isSelected"] == true { &r["onDeselectedCommand"] } else { &r["navigationEndpoint"] };
                if !title.is_empty() { out.push(json!({"title":title,"params":find_string(nav,"params").unwrap_or(""),"browseId":find_string(nav,"browseId").unwrap_or("FEmusic_home"),"selected":r["isSelected"] == true})); }
                return;
            }
            if let Some(r) = m.get("musicNavigationButtonRenderer") {
                let title = text(&r["buttonText"]);
                if !title.is_empty() { out.push(json!({"title":title,"params":find_string(&r["clickCommand"],"params").unwrap_or(""),"browseId":find_string(&r["clickCommand"],"browseId").unwrap_or(""),"selected":false})); }
                return;
            }
            for child in m.values() { chips(child, out, depth + 1); }
        }
        Value::Array(a) => for child in a { chips(child, out, depth + 1); },
        _ => (),
    }
}

fn lrc(input: &str) -> Value {
    let mut lines: Vec<(u64, String)> = Vec::new();
    let mut offset: i64 = 0;
    for line in input.lines() {
        if let Some(s) = line.trim().strip_prefix("[offset:").and_then(|s| s.strip_suffix(']')) { offset = s.parse().unwrap_or(0); }
        let mut rest = line;
        let mut times = Vec::new();
        while let Some(s) = rest.strip_prefix('[') {
            let Some((stamp, tail)) = s.split_once(']') else { break; };
            if let Some((m, s)) = stamp.split_once(':') {
                if let (Ok(m), Ok(s)) = (m.parse::<u64>(), s.parse::<f64>()) {
                    if s.is_finite() && (0.0..60.0).contains(&s) && m < 10000 { times.push(m * 60_000 + (s * 1000.0) as u64); }
                }
            }
            rest = tail;
        }
        for time in times { lines.push((time, rest.trim().to_owned())); }
    }
    lines.sort_by_key(|v| v.0);
    json!(lines.into_iter().map(|(ms, t)| json!({"ms":(ms as i64 + offset).max(0),"text":t})).collect::<Vec<_>>())
}

fn authorization(cookie: &str, origin: &str, timestamp: u64) -> Option<String> {
    let parts = [("SAPISIDHASH", "SAPISID"), ("SAPISID1PHASH", "__Secure-1PAPISID"), ("SAPISID3PHASH", "__Secure-3PAPISID")].into_iter().filter_map(|(scheme, key)| {
        let sid = cookie.split(';').filter_map(|s| s.trim().split_once('=')).find(|(name, value)| *name == key && !value.is_empty())?.1;
        let digest = Sha1::digest(format!("{timestamp} {sid} {origin}"));
        Some(format!("{scheme} {timestamp}_{digest:x}"))
    }).collect::<Vec<_>>();
    (!parts.is_empty()).then(|| parts.join(" "))
}

fn number(v: &Value) -> u64 { v.as_u64().or_else(|| v.as_str()?.parse().ok()).unwrap_or(0) }
fn valid_stream_url(url: &str) -> bool { url.starts_with("https://") && !url.contains(['\r','\n']) }
fn loudness(d: &Value, f: &Value) -> Value {
    if f["loudnessDb"].is_number() { return f["loudnessDb"].clone(); }
    let config = &d["playerConfig"]["audioConfig"];
    config["trackAbsoluteLoudnessLkfs"].as_f64().or(config["perceptualLoudnessDb"].as_f64())
        .map(|n| json!(n - config["loudnessTargetLkfs"].as_f64().unwrap_or(-14.0))).unwrap_or(Value::Null)
}

fn stream_metadata(d: &Value, f: &Value, url: &str, hls: bool) -> Value {
    let duration = number(&f["approxDurationMs"]);
    json!({"url":url,"hls":hls,"ua":PLAYER_UA,"itag":f["itag"],
        "size":number(&f["contentLength"]),"contentLength":number(&f["contentLength"]),
        "durationMs":if duration > 0 {duration} else {number(&d["videoDetails"]["lengthSeconds"]) * 1000},
        "mime":if hls {"application/x-mpegURL"} else {f["mimeType"].as_str().unwrap_or("audio/mp4")},
        "bitrate":f["averageBitrate"].as_u64().or(f["bitrate"].as_u64()).unwrap_or(0),"loudnessDb":loudness(d,f)})
}

fn tag_m4a(v: &Value) -> Result<Value, String> {
    let path = std::path::Path::new(v["path"].as_str().ok_or("Missing audio path")?);
    if !path.is_absolute() || !path.is_file() { return Err("Audio path must be an existing absolute file".into()); }
    let mut tag = mp4ameta::Tag::read_from_path(path).map_err(|e| format!("Cannot read M4A tags: {e}"))?;
    for key in ["title","artist","album"] {
        if let Some(value) = v[key].as_str().filter(|s| !s.is_empty()) {
            match key { "title" => tag.set_title(value), "artist" => {tag.set_artist(value);tag.set_album_artist(value)}, _ => tag.set_album(value) }
        }
    }
    if v["explicit"] == true { tag.set_advisory_rating(mp4ameta::AdvisoryRating::Explicit); }
    let mut artwork = false;
    if let Some(art_path) = v["artPath"].as_str().filter(|s| !s.is_empty()) {
        let image = std::fs::read(art_path).map_err(|e| format!("Cannot read artwork: {e}"))?;
        let image = if image.starts_with(&[0xff,0xd8]) {mp4ameta::Img::jpeg(image)}
            else if image.starts_with(&[0x89,b'P',b'N',b'G']) {mp4ameta::Img::png(image)}
            else {return Err("M4A artwork must be JPEG or PNG".into())};
        tag.set_artwork(image); artwork = true;
    }
    let temp = path.with_file_name(format!(".{}-{}-tagging.m4a",path.file_name().and_then(|s| s.to_str()).unwrap_or("audio"),std::process::id()));
    std::fs::copy(path,&temp).map_err(|e| format!("Cannot prepare M4A tag edit: {e}"))?;
    let result = tag.write_to_path(&temp).map_err(|e| format!("Cannot write M4A tags: {e}"))
        .and_then(|_| std::fs::rename(&temp,path).map_err(|e| format!("Cannot replace tagged M4A: {e}")));
    if result.is_err() { let _ = std::fs::remove_file(&temp); }
    result?;
    Ok(json!({"tagged":true,"artwork":artwork}))
}

pub fn dispatch(v: Value) -> Result<Value, String> {
    match v["op"].as_str().unwrap_or_default() {
        "request" => {
            let endpoint = v["endpoint"].as_str().unwrap_or("browse");
            if !["search", "browse", "next", "player", "like/like", "like/dislike", "like/removelike", "playlist/create", "playlist/delete", "browse/edit_playlist", "subscription/subscribe", "subscription/unsubscribe", "feedback", "account/account_menu", "music/get_search_suggestions"].contains(&endpoint) { return Err("Unsupported endpoint".into()); }
            let mut body = v["body"].clone();
            if !body.is_object() { return Err("Request body must be an object".into()); }
            let player_endpoint = endpoint == "player";
            let player = player_endpoint && v["webPlayer"] != true;
            let version = v["version"].as_str().unwrap_or("1.20261006.10.00");
            body["context"] = if player {
                json!({"client":{"clientName":"VISIONOS","clientVersion":"1.02","deviceMake":"Apple","deviceModel":"RealityDevice17,1","userAgent":PLAYER_UA,"osName":"visionOS","osVersion":"26.5.23O471","hl":"en","gl":"US","visitorData":v["visitor"].as_str().unwrap_or("")}})
            } else { json!({"client":{"clientName":"WEB_REMIX","clientVersion":version,"hl":"en","gl":"US","visitorData":v["visitor"].as_str().unwrap_or("")}}) };
            if player_endpoint { body["contentCheckOk"] = json!(true); body["racyCheckOk"] = json!(true); }
            if v["restricted"] == true { body["context"]["user"] = json!({"enableSafetyMode":true}); }
            let origin = if player { "https://www.youtube.com" } else { "https://music.youtube.com" };
            let cookie = v["cookie"].as_str().filter(|s| !s.is_empty()).unwrap_or("SOCS=CAISAiAD");
            if cookie.len() > 65536 || cookie.contains(['\r','\n']) { return Err("Invalid session cookies".into()); }
            let mut headers = json!({"User-Agent":if player {PLAYER_UA} else {WEB_UA},"Origin":origin,"X-Origin":origin,"X-YouTube-Client-Name":if player {"101"} else {"67"},"X-YouTube-Client-Version":if player {"1.02"} else {version},"X-Goog-Visitor-Id":v["visitor"].as_str().unwrap_or(""),"Cookie":cookie});
            let timestamp = v["timestamp"].as_u64().unwrap_or_else(|| std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d|d.as_secs()).unwrap_or(0));
            if let Some(auth) = authorization(cookie,origin,timestamp) {
                headers["Authorization"] = json!(auth);
                headers["X-Goog-AuthUser"] = json!(v["sessionIndex"].as_u64().unwrap_or(0).to_string());
                if let Some(page_id) = v["pageId"].as_str().filter(|s| !s.contains(['\r','\n'])) {headers["X-Goog-PageId"] = json!(page_id);}
            }
            Ok(json!({"url":format!("{origin}/youtubei/v1/{endpoint}?prettyPrint=false"),"body":body,"headers":headers}))
        }
        "parse" => {
            let mut items = Vec::new();
            collect(&v["data"], &mut items, &mut HashSet::new(), 0);
            let mut shelves = Vec::new(); let mut filters = Vec::new();
            sections(&v["data"], &mut shelves, 0); chips(&v["data"], &mut filters, 0);
            Ok(json!({"items":items,"sections":shelves,"chips":filters,"continuation":find_string(&v["data"],"continuation").or_else(|| find_string(&v["data"],"token"))}))
        }
        "stream" => {
            let d = &v["data"];
            if d["playabilityStatus"]["status"] != "OK" {
                return Err(d["playabilityStatus"]["reason"].as_str().unwrap_or("YouTube refused playback. Try another song or network.").into());
            }
            let formats = d["streamingData"]["adaptiveFormats"].as_array().map(Vec::as_slice).unwrap_or_default();
            let hls = d["streamingData"]["hlsManifestUrl"].as_str().filter(|u| valid_stream_url(u));
            let hls_format = formats.iter().find(|f| f["itag"] == 140).unwrap_or(&Value::Null);
            if v["video"] == true {
                return hls.map(|url| stream_metadata(d,hls_format,url,true)).ok_or_else(|| "YouTube did not return a playable video manifest".to_owned());
            }
            // HLS avoids network experiments that cut off progressive files after 1 MiB.
            if v["preferProgressive"] != true { if let Some(url) = hls {return Ok(stream_metadata(d,hls_format,url,true));} }
            let direct = formats.iter().filter(|f| {
                let mime = f["mimeType"].as_str().unwrap_or("");
                let known = [139,140,141,249,250,251,774].contains(&f["itag"].as_u64().unwrap_or(0));
                (mime.starts_with("audio/") || (mime.is_empty() && known)) &&
                    (v["preferAac"] != true || mime.contains("mp4") || [139,140,141].contains(&f["itag"].as_u64().unwrap_or(0))) &&
                    f["url"].as_str().is_some_and(valid_stream_url)
            }).max_by_key(|f| {
                let opus = f["mimeType"].as_str().is_some_and(|m| m.contains("opus")) || [249,250,251,774].contains(&f["itag"].as_u64().unwrap_or(0));
                (v["preferred"] == "opus" && opus, f["averageBitrate"].as_u64().or(f["bitrate"].as_u64()).unwrap_or(0))
            });
            if let Some(format) = direct {return Ok(stream_metadata(d,format,format["url"].as_str().unwrap(),false));}
            if let Some(url) = hls {return Ok(stream_metadata(d,hls_format,url,true));}
            if formats.iter().any(|f| f.get("signatureCipher").is_some()) {
                return Err("This account's stream requires a player signature. No playable direct audio was returned.".into());
            }
            Err("No playable audio stream was returned".into())
        }
        "hls" => {
            let manifest = v["text"].as_str().unwrap_or_default();
            let url = manifest.lines().filter(|l| l.starts_with("#EXT-X-MEDIA:") && l.contains("TYPE=AUDIO"))
                .find_map(|l| if l.contains("GROUP-ID=\"234\"") { l.split("URI=\"").nth(1)?.split('"').next() } else { None });
            Ok(json!({"url":url}))
        }
        "lrc" => Ok(lrc(v["text"].as_str().unwrap_or_default())),
        "tag_m4a" => tag_m4a(&v),
        _ => Err("Unknown native operation".into()),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_enov_ytm_NativeCore_call(mut env: jni::JNIEnv, _: jni::objects::JObject, input: jni::objects::JString) -> jni::sys::jstring {
    let answer = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let input: String = env.get_string(&input).map_err(|e| e.to_string())?.into();
        let v = serde_json::from_str(&input).map_err(|e| e.to_string())?;
        dispatch(v)
    }));
    let result = match answer { Ok(Ok(data)) => json!({"data":data}), Ok(Err(error)) => json!({"error":error}), Err(_) => json!({"error":"Native operation failed"}) };
    env.new_string(result.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn parses_rows_and_deduplicates() {
        let row = json!({"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}});
        let d = dispatch(json!({"op":"parse","data":[row.clone(),row]})).unwrap();
        assert_eq!(d["items"].as_array().unwrap().len(),1); assert_eq!(d["items"][0]["title"],"A song");
    }
    #[test] fn lyrics_multiple_timestamps_offset_and_invalid() {
        assert_eq!(lrc("[offset:-500]\n[00:01.20][00:03.00]Hello\n[garbage]skip\n[00:NaN]skip"),json!([{"ms":700,"text":"Hello"},{"ms":2500,"text":"Hello"}]));
    }
    #[test] fn refuses_errors_and_prefers_hls() {
        assert!(dispatch(json!({"op":"stream","data":{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}})).is_err());
        let d=dispatch(json!({"op":"stream","data":{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://example.com/audio.m3u8","adaptiveFormats":[{"itag":140,"url":"progressive"}]}}})).unwrap();
        assert_eq!(d["hls"],true);
    }
    #[test] fn request_boundary_and_audio_rendition() {
        assert!(dispatch(json!({"op":"request","endpoint":"../../evil","body":{}})).is_err());
        let web = dispatch(json!({"op":"request","endpoint":"browse","body":{},"version":"live-version","visitor":"visitor-id"})).unwrap();
        assert_eq!(web["body"]["context"]["client"]["clientVersion"], "live-version");
        assert_eq!(web["headers"]["X-Goog-Visitor-Id"], "visitor-id");
        assert!(web["headers"]["User-Agent"].as_str().unwrap().contains("Chrome/"));
        assert_eq!(dispatch(json!({"op":"hls","text":"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"234\",URI=\"https://a/audio.m3u8\""})).unwrap()["url"],"https://a/audio.m3u8");
    }
}
