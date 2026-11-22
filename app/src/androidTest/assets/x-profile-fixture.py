import json
from urllib.parse import urlencode
from gallery_dl.extractor.twitter import TwitterAPI
from gallery_dl.version import __version__
import omni_media as media

def tweet(number):
    return {'entryId': 'tweet-' + number, 'content': {'itemContent': {'tweet_results': {'result': {
        'rest_id': number, 'legacy': {'id_str': number, 'user_id_str': '42', 'extended_entities': {'media': [
            {'id_str': '900', 'type': 'photo', 'media_url_https': 'https://pbs.twimg.com/media/fixture' + number + '.jpg',
             'original_info': {'width': 2048, 'height': 3072}}]}}}}}}}

def call(self, endpoint, params):
    variables = json.loads(params['variables'])
    assert variables['userId'] == '42' and endpoint.endswith('/UserPhotoTimeline')
    more = not variables.get('cursor')
    assert more or variables['cursor'] == 'next'
    entries = [tweet('101' if more else '102')]
    if more: entries.append({'entryId': 'cursor-bottom-1', 'content': {'cursorType': 'Bottom', 'value': 'next'}})
    return {'data': {'user': {'result': {'rest_id': '42', 'timeline': {'timeline': {'instructions': [
        {'type': 'TimelineAddEntries', 'entries': entries}]}}}}}}

TwitterAPI.user_by_screen_name = lambda self, handle: {'rest_id': '42', 'core': {'screen_name': handle}}
TwitterAPI._call = call
first = media.resolve_x_profile_page('https://x.com/hazzi_808')
last = media.resolve_x_profile_page('https://x.com/hazzi_808', cursor=first['profileCursor'], expected_user=first['profileUserId'])
assert not first['profileComplete'] and last['profileComplete']
print(json.dumps({'version': __version__, 'pages': [first, last]}))
