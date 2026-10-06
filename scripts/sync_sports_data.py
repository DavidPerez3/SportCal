#!/usr/bin/env python3
import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'data' / 'sports-calendar.json'
ASSET = ROOT / 'app' / 'src' / 'main' / 'assets' / 'sports-calendar.json'
MADRID = ZoneInfo('Europe/Madrid')
UA = 'SportCal/0.3 (+https://github.com/DavidPerez3/SportCal)'

SOURCES = {
    'laliga': ('https://fixturedownload.com/feed/json/la-liga-2026', 'LaLiga', 300),
    'champions': ('https://fixturedownload.com/feed/json/champions-league-2026', 'Champions League', 100),
    'europa': ('https://fixturedownload.com/feed/json/europa-league-2026', 'Europa League', 100),
}
EUROLEAGUE_URL = 'https://api-live.euroleague.net/v2/competitions/E/seasons/E2026/games'

STATIC_EVENTS = [
    {'d':'2026-09-21','e':'2026-10-06','c':'intl','t':'Parón de selecciones','x':'Ventana FIFA · hasta 6 oct','h':'Todo el día'},
    {'d':'2026-11-09','e':'2026-11-17','c':'intl','t':'Parón de selecciones','x':'Ventana FIFA · hasta 17 nov','h':'Todo el día'},
    {'d':'2027-03-22','e':'2027-03-30','c':'intl','t':'Parón de selecciones','x':'Ventana FIFA · hasta 30 mar','h':'Todo el día'},
    {'d':'2027-06-07','e':'2027-06-15','c':'intl','t':'Parón de selecciones','x':'Ventana FIFA · hasta 15 jun','h':'Todo el día'},
    {'d':'2026-08-23','e':'2026-09-13','c':'tennis','t':'US Open','x':'Grand Slam · Nueva York','h':'Todo el día'},
    {'d':'2027-01-11','e':'2027-01-31','c':'tennis','t':'Australian Open','x':'Grand Slam · Melbourne','h':'Todo el día'},
    {'d':'2027-05-17','e':'2027-06-06','c':'tennis','t':'Roland-Garros','x':'Grand Slam · París','h':'Todo el día'},
    {'d':'2027-06-28','e':'2027-07-11','c':'tennis','t':'Wimbledon','x':'Grand Slam · Londres','h':'Todo el día'},
]

def fetch_json(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=45) as response:
        raw = response.read()
    return json.loads(raw.decode('utf-8-sig'))

def load_previous():
    if not OUT.exists():
        return {'events': [], 'sources': {}}
    try:
        return json.loads(OUT.read_text(encoding='utf-8'))
    except Exception:
        return {'events': [], 'sources': {}}

def parse_utc(value):
    if not value:
        raise ValueError('missing date')
    s = str(value).strip().replace(' ', 'T')
    if s.endswith('Z'):
        s = s[:-1] + '+00:00'
    dt = datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(MADRID)

def football_events(code, url, label):
    rows = fetch_json(url)
    if not isinstance(rows, list):
        raise ValueError(f'{code}: feed is not a list')
    out = []
    for row in rows:
        try:
            dt = parse_utc(row.get('DateUtc'))
            home = str(row.get('HomeTeam') or '').strip()
            away = str(row.get('AwayTeam') or '').strip()
            if not home or not away:
                continue
            rnd = row.get('RoundNumber')
            meta = label + (f' · Jornada {rnd}' if rnd not in (None, '') else '')
            out.append({
                'd': dt.date().isoformat(), 'c': code,
                't': f'{home} · {away}', 'x': meta, 'h': dt.strftime('%H:%M')
            })
        except Exception:
            continue
    return out

def pick_name(side):
    if not isinstance(side, dict):
        return ''
    club = side.get('club') if isinstance(side.get('club'), dict) else side
    for key in ('name', 'editorialName', 'abbreviatedName', 'tvCode', 'code'):
        value = club.get(key) if isinstance(club, dict) else None
        if value:
            return str(value).strip()
    return ''

def euroleague_events():
    payload = fetch_json(EUROLEAGUE_URL)
    rows = payload.get('data', []) if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        raise ValueError('euroleague: data is not a list')
    out = []
    for game in rows:
        try:
            home = pick_name(game.get('local'))
            away = pick_name(game.get('road'))
            if not home or not away:
                continue
            raw_date = game.get('utcDate') or game.get('date') or game.get('localDate')
            dt = parse_utc(raw_date)
            rnd = game.get('round')
            round_name = game.get('roundName') or game.get('roundAlias')
            if rnd not in (None, ''):
                meta = f'Euroliga · Jornada {rnd}'
            elif round_name:
                meta = f'Euroliga · {round_name}'
            else:
                meta = 'Euroliga'
            confirmed_hour = game.get('confirmedHour')
            hour = dt.strftime('%H:%M') if confirmed_hour is not False else 'Por confirmar'
            out.append({
                'd': dt.date().isoformat(), 'c': 'euroleague',
                't': f'{home} · {away}', 'x': meta, 'h': hour
            })
        except Exception:
            continue
    return out

def dedupe(events):
    seen = set()
    out = []
    for e in events:
        key = (e.get('c'), e.get('d'), e.get('e',''), e.get('t'))
        if key in seen:
            continue
        seen.add(key)
        out.append(e)
    return sorted(out, key=lambda e: (e.get('d',''), e.get('h',''), e.get('c',''), e.get('t','')))

def main():
    previous = load_previous()
    previous_events = previous.get('events', []) if isinstance(previous, dict) else []
    final = []
    status = {}
    errors = []

    for code, (url, label, minimum) in SOURCES.items():
        try:
            events = football_events(code, url, label)
            if len(events) < minimum:
                raise ValueError(f'only {len(events)} events, expected at least {minimum}')
            final.extend(events)
            status[code] = {'ok': True, 'count': len(events), 'url': url}
            print(f'{code}: {len(events)} events')
        except Exception as exc:
            fallback = [e for e in previous_events if e.get('c') == code]
            status[code] = {'ok': False, 'count': len(fallback), 'url': url, 'error': str(exc)}
            if fallback:
                final.extend(fallback)
                print(f'{code}: sync failed, kept {len(fallback)} cached events: {exc}', file=sys.stderr)
            else:
                errors.append(f'{code}: {exc}')

    try:
        events = euroleague_events()
        if len(events) < 300:
            raise ValueError(f'only {len(events)} events, expected at least 300')
        final.extend(events)
        status['euroleague'] = {'ok': True, 'count': len(events), 'url': EUROLEAGUE_URL}
        print(f'euroleague: {len(events)} events')
    except Exception as exc:
        fallback = [e for e in previous_events if e.get('c') == 'euroleague']
        status['euroleague'] = {'ok': False, 'count': len(fallback), 'url': EUROLEAGUE_URL, 'error': str(exc)}
        if fallback:
            final.extend(fallback)
            print(f'euroleague: sync failed, kept {len(fallback)} cached events: {exc}', file=sys.stderr)
        else:
            errors.append(f'euroleague: {exc}')

    final.extend(STATIC_EVENTS)
    final = dedupe(final)
    if errors:
        raise SystemExit('Initial sync incomplete: ' + ' | '.join(errors))

    payload = {
        'schemaVersion': 1,
        'season': '2026/27',
        'generatedAt': datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),
        'timezone': 'Europe/Madrid',
        'events': final,
        'sources': status,
        'counts': {code: sum(1 for e in final if e.get('c') == code) for code in ('laliga','champions','europa','intl','euroleague','tennis')}
    }
    text = json.dumps(payload, ensure_ascii=False, separators=(',', ':')) + '\n'
    OUT.parent.mkdir(parents=True, exist_ok=True)
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding='utf-8')
    ASSET.write_text(text, encoding='utf-8')
    print('total:', len(final), payload['counts'])

if __name__ == '__main__':
    main()
