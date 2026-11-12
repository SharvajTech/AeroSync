"""
The standalone OpenSky fetcher (data/opensky_fetcher.py) with a mocked
`requests`: credentials come only from the environment, OAuth2 tokens are
reused, and a rate limit stops the run instead of spending more credits.
No test talks to OpenSky.
"""
import importlib.util
import re

import pytest

from conftest import ML_DIR

FETCHER = ML_DIR / "data" / "opensky_fetcher.py"


class Reply:
    def __init__(self, status, body=None, headers=None):
        self.status_code = status
        self._body = body
        self.headers = headers or {}

    def json(self):
        return self._body


@pytest.fixture
def fetcher(monkeypatch):
    def load(client_id="", client_secret=""):
        monkeypatch.setenv("OPENSKY_CLIENT_ID", client_id)
        monkeypatch.setenv("OPENSKY_CLIENT_SECRET", client_secret)
        spec = importlib.util.spec_from_file_location("opensky_fetcher_under_test", FETCHER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        monkeypatch.setattr(module.time, "sleep", lambda seconds: None)
        return module
    return load


def test_no_credentials_are_written_in_the_source():
    source = FETCHER.read_text(encoding="utf-8")
    assert not re.search(r'OPENSKY_(USERNAME|PASSWORD)\s*=', source)
    assert not re.search(r'OPENSKY_CLIENT_(ID|SECRET)\s*=\s*["\']', source), "use os.environ"
    assert "auth=(" not in source, "OpenSky no longer accepts basic authentication"


def test_hyderabad_uses_the_current_airport(fetcher):
    assert fetcher().INDIAN_AIRPORTS["HYD"] == "VOHS"


def test_anonymous_live_states_send_no_authorization(fetcher, monkeypatch):
    module = fetcher()
    calls = []

    def get(url, params=None, headers=None, timeout=None):
        calls.append((url, headers))
        return Reply(200, {"time": 1, "states": [["800c1a", "IGO5311 ", "India", 1, 1, 72.9, 19.1, 900.0,
                                                  False, 75.0, 270.0, -3.0, None, 950.0, None, False, 0]]})

    monkeypatch.setattr(module.requests, "get", get)
    monkeypatch.setattr(module.requests, "post", lambda *a, **k: pytest.fail("no token without credentials"))
    states = module.fetch_live_states("VABB")
    assert len(states) == 1
    assert calls[0][1] == {}
    # Historical data needs credentials: skipped without any request.
    assert module.fetch_historical_with_auth("VABB") == []
    assert len(calls) == 1


def test_client_credentials_get_one_token_that_is_reused(fetcher, monkeypatch):
    module = fetcher("aerosync-test-client", "not-a-real-secret")
    posts, gets = [], []

    def post(url, data=None, timeout=None):
        posts.append(data)
        return Reply(200, {"access_token": "token-1", "expires_in": 1800})

    def get(url, params=None, headers=None, timeout=None):
        gets.append(headers)
        return Reply(200, [] if "flights" in url else {"time": 1, "states": []})

    monkeypatch.setattr(module.requests, "post", post)
    monkeypatch.setattr(module.requests, "get", get)
    module.fetch_live_states("VABB")
    module.fetch_historical_with_auth("VIDP")

    assert len(posts) == 1
    assert posts[0]["grant_type"] == "client_credentials"
    assert posts[0]["client_id"] == "aerosync-test-client"
    assert all(h == {"Authorization": "Bearer token-1"} for h in gets)


def test_historical_queries_stay_within_two_days(fetcher, monkeypatch):
    module = fetcher("aerosync-test-client", "not-a-real-secret")
    seen = []
    monkeypatch.setattr(module.requests, "post",
                        lambda *a, **k: Reply(200, {"access_token": "t", "expires_in": 1800}))
    monkeypatch.setattr(module.requests, "get",
                        lambda url, params=None, headers=None, timeout=None: seen.append(params) or Reply(200, []))
    module.fetch_historical_with_auth("VABB", days_back=7)
    assert seen[0]["end"] - seen[0]["begin"] <= 2 * 86400 + 1


def test_a_rate_limit_stops_the_whole_run(fetcher, monkeypatch, capsys):
    module = fetcher()
    calls = []

    def get(url, params=None, headers=None, timeout=None):
        calls.append(url)
        return Reply(429, None, {"X-Rate-Limit-Retry-After-Seconds": "3600"})

    monkeypatch.setattr(module.requests, "get", get)
    frame = module.fetch_all_indian_airports()
    assert frame.empty
    assert len(calls) == 1, "no further airport is asked after HTTP 429"
    assert "retry after 3600 s" in capsys.readouterr().out


def test_a_failed_token_request_does_not_print_the_secret(fetcher, monkeypatch, capsys):
    module = fetcher("aerosync-test-client", "very-secret-value")
    monkeypatch.setattr(module.requests, "post", lambda *a, **k: Reply(401, {"error": "invalid_client"}))
    monkeypatch.setattr(module.requests, "get", lambda *a, **k: pytest.fail("no data request without a token"))
    assert module.fetch_live_states("VABB") == []
    assert "very-secret-value" not in capsys.readouterr().out
