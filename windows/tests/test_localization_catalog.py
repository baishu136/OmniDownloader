from tools.audit_localization import audit, android_tables


def test_all_owned_static_ui_copy_has_locale_entries_and_safe_placeholders():
    report = audit()
    assert not report['errors'], '\n'.join(report['errors'])


def test_download_type_labels_match_on_all_android_surfaces():
    tables = android_tables()
    for language, names in {
        'zh-CN': ('视频', '纯画面', '音频', 'GIF'),
        'zh-TW': ('視頻', '純畫面', '音訊', 'GIF'),
        'en': ('Video', 'Video only', 'Audio', 'GIF'),
        'ja': ('動画', '映像のみ', '音声', 'GIF'),
    }.items():
        table = tables[language]
        for prefix in ('tab', 'badge'):
            assert tuple(table[prefix + field] for field in ('Video', 'Mute', 'Audio', 'Gif')) == names
        assert table['typeVideo'] == names[0] and table['typeAudio'] == names[2]
