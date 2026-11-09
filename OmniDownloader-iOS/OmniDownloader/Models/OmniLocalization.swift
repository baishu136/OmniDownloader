import Foundation

enum OmniLocalization{
    private static let values:[String:[String:String]]={
        guard let file=Bundle.main.url(forResource:"strings",withExtension:"json"),let data=try? Data(contentsOf:file),let decoded=try? JSONDecoder().decode([String:[String:String]].self,from:data) else{return [:]};return decoded
    }()
    static func text(_ source:String,language:String?=nil)->String{
        let choice=language ?? UserDefaults.standard.string(forKey:"omni.language") ?? "system"
        let locale=choice=="system" ? (Locale.preferredLanguages.first ?? "en"):choice
        let code:String
        if locale.hasPrefix("zh"){code=locale.contains("Hant") || locale.contains("TW") || locale.contains("HK") ? "zh-TW":"zh-CN"}
        else if locale.hasPrefix("ja"){code="ja"}else{code="en"}
        return values[code]?[source] ?? source
    }
}
