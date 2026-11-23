import Foundation

/// YouTube helpers shared with Android (mirror of YouTube.kt / Dial.kt). Foundation only, tested on Linux.
enum YouTubeShared {
    static let nsMdx = "urn:x-cast:com.google.youtube.mdx"

    /// The video ID from a YouTube link (watch, youtu.be, shorts, live, embed, music) or a bare ID.
    static func videoId(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let id = "[A-Za-z0-9_-]{11}"
        let patterns = [
            "(?:youtube\\.com|youtube-nocookie\\.com)/(?:watch\\?(?:[^#\\s]*&)?v=|shorts/|live/|embed/|v/)(\(id))",
            "youtu\\.be/(\(id))",
            "music\\.youtube\\.com/watch\\?(?:[^#\\s]*&)?v=(\(id))",
        ]
        for p in patterns { if let m = first(p, in: text) { return m } }
        let bare = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return first("^(\(id))$", in: bare)
    }

    /// The screen ID from the Chromecast's YouTube "mdxSessionStatus" message.
    static func screenId(fromMdx payload: String) -> String? {
        guard payload.contains("mdxSessionStatus") else { return nil }
        return first("\"screenId\"\\s*:\\s*\"([^\"]+)\"", in: payload)
    }

    /// A tag's text from a DIAL app-state XML (Samsung TVs): <screenId>…</screenId>, <state>…</state>.
    static func dialTag(_ xml: String, _ name: String) -> String? {
        first("<\(name)>\\s*([^<]*?)\\s*</\(name)>", in: xml).flatMap { $0.isEmpty ? nil : $0 }
    }

    static func loungeToken(fromJson body: String) -> String? { first("\"loungeToken\"\\s*:\\s*\"([^\"]+)\"", in: body) }
    static func sid(fromBind body: String) -> String? { first("\"c\",\"(.*?)\",", in: body) }
    static func gsessionId(fromBind body: String) -> String? { first("\"S\",\"(.*?)\"\\]", in: body) }

    static func formEncode(_ pairs: [(String, String)]) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._*")
        func enc(_ s: String) -> String { (s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s).replacingOccurrences(of: "%20", with: "+") }
        return pairs.map { enc($0.0) + "=" + enc($0.1) }.joined(separator: "&")
    }

    private static func first(_ pattern: String, in s: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }
}
