/* 3개 페이지가 공유하는 최소 헬퍼. 프레임워크도 빌드 단계도 없다. */

/* API는 이 페이지와 같은 디렉터리 아래에 있다.
 * context-path(/reserve)를 하드코딩하지 않으므로 서브도메인으로 옮겨도 그대로 동작한다. */
const BASE = location.pathname.replace(/[^/]*$/, '');

const KST = 'Asia/Seoul';

async function api(path, options = {}) {
	// headers를 먼저 빼낸다. 그냥 {...options}를 펼치면 그 안의 headers가
	// 아래에서 합쳐둔 headers를 통째로 덮어써서 Content-Type이 사라지고,
	// 본문 있는 요청이 415로 거절된다. 구조분해로 그 실수를 아예 막는다.
	const { headers, ...rest } = options;

	let res;
	try {
		res = await fetch(BASE + path, {
			...rest,
			headers: { 'Content-Type': 'application/json', ...headers },
		});
	} catch {
		// fetch는 네트워크 실패에만 reject한다. 브라우저 기본 문구("Failed to fetch")가
		// 그대로 화면에 뜨지 않도록 여기서 한국어로 바꾼다.
		throw new Error('연결에 실패했습니다. 네트워크를 확인하고 다시 시도해 주세요.');
	}

	// ⚠ res.ok를 먼저 봐야 한다. 본문 없는 응답을 먼저 걸러내면 관리자 인증 실패(401)처럼
	// 본문 없이 오는 '에러'까지 성공으로 삼켜서 null을 돌려주고, 호출부는 실패를 알 수 없다.
	const empty = res.status === 204 || res.headers.get('content-length') === '0';
	const text = empty ? '' : await res.text();
	let data = null;
	try {
		data = text ? JSON.parse(text) : null;
	} catch {
		data = null; // JSON이 아닌 에러 페이지(nginx 502 등)
	}

	if (!res.ok) {
		if (res.status === 401) {
			throw new Error('인증에 실패했습니다.');
		}
		throw new Error(data?.detail || data?.message || `요청에 실패했습니다 (${res.status})`);
	}
	return data;
}

/* 상단 메뉴. 여섯 페이지가 같은 줄을 쓰므로 한 곳에서 만든다 —
 * 복사해 두면 메뉴 하나 바꿀 때마다 여섯 곳을 고쳐야 하고 언젠가 한 곳을 빠뜨린다.
 *
 * 사전 참가 등록은 메뉴에서 뺐다(요청). 되돌리려면 acts에 한 줄만 추가하면 된다:
 *   add(acts, 'register', 'https://eastpeace.kr/conference-register.html?code=…',
 *       '사전 참가 등록', true);
 */
function renderNav(current) {
	const host = document.getElementById('sitenav');
	if (!host) {
		return;
	}
	host.className = 'sitenav';

	const box = document.createElement('div');
	box.className = 'sitenav-in';

	const home = document.createElement('a');
	home.className = 'sitenav-home';
	home.href = 'index.html';
	const mark = document.createElement('img');
	mark.src = 'favicon.svg';
	mark.alt = '';
	mark.width = 24;
	mark.height = 24;
	const label = document.createElement('span');
	label.textContent = '온라인 채용박람회';
	home.append(mark, label);
	box.append(home);

	const add = (parent, key, href, text, external) => {
		const a = document.createElement('a');
		a.href = href;
		a.textContent = text;
		if (external) {
			a.rel = 'noopener';
		}
		if (key === current) {
			a.setAttribute('aria-current', 'page');
		}
		parent.append(a);
	};

	const links = document.createElement('nav');
	links.className = 'sitenav-links';
	add(links, 'about', 'about.html', '행사 개요');
	add(links, 'company', 'company.html', '채용관');
	box.append(links);

	const acts = document.createElement('div');
	acts.className = 'sitenav-acts';
	add(acts, 'briefing', 'briefing.html', '기업 설명회');
	add(acts, 'docs', 'docs.html', '서류 등록');
	const mine = document.createElement('a');
	mine.className = 'sitenav-mine';
	mine.href = 'my.html';
	mine.textContent = '내 예약';
	if (current === 'mine') {
		mine.setAttribute('aria-current', 'page');
	}
	acts.append(mine);
	box.append(acts);

	host.replaceChildren(box);
}

/* 서버는 UTC Instant를 준다. 화면은 항상 한국시간으로 읽는다. */
function hhmm(iso) {
	return new Date(iso).toLocaleTimeString('ko-KR', {
		timeZone: KST, hour: '2-digit', minute: '2-digit', hour12: false,
	});
}

function dayLabel(ymd) {
	const d = new Date(ymd + 'T00:00:00+09:00');
	return d.toLocaleDateString('ko-KR', {
		timeZone: KST, month: 'long', day: 'numeric', weekday: 'short',
	});
}

function fullWhen(iso) {
	return new Date(iso).toLocaleString('ko-KR', {
		timeZone: KST, month: 'long', day: 'numeric', weekday: 'short',
		hour: '2-digit', minute: '2-digit', hour12: false,
	});
}

/** 오늘(KST) 기준 offset일 뒤의 YYYY-MM-DD */
function ymd(offset = 0) {
	const parts = new Intl.DateTimeFormat('en-CA', {
		timeZone: KST, year: 'numeric', month: '2-digit', day: '2-digit',
	}).format(new Date());
	return offset === 0 ? parts : addDays(parts, offset);
}

/** YYYY-MM-DD 에서 며칠 이동. UTC 자정으로 파싱해 UTC로 더하고 UTC로 되돌린다 —
 *  로컬 타임존이 개입하면 KST 자정(=전날 15:00 UTC)에서 날짜가 하루 밀린다. */
function addDays(ymdStr, delta) {
	const d = new Date(ymdStr + 'T00:00:00Z');
	d.setUTCDate(d.getUTCDate() + delta);
	return d.toISOString().slice(0, 10);
}

/** "10:00:00" + n분 단위로 운영시간 전체를 훑어 슬롯 시작 시각(Date)을 만든다.
 *  서버 Slots.forBooth와 같은 규칙 — 끝을 넘기는 슬롯은 만들지 않는다. */
function slotGrid(ymdStr, from, to, minutes) {
	const [fh, fm] = from.split(':').map(Number);
	const [th, tm] = to.split(':').map(Number);
	const start = fh * 60 + fm;
	const end = th * 60 + tm;
	const out = [];
	for (let m = start; m + minutes <= end; m += minutes) {
		const hh = String(Math.floor(m / 60)).padStart(2, '0');
		const mm = String(m % 60).padStart(2, '0');
		out.push(new Date(`${ymdStr}T${hh}:${mm}:00+09:00`));
	}
	return out;
}

/** role="alert"를 붙여 스크린리더가 실패를 즉시 읽도록 한다. */
function showError(el, message) {
	el.innerHTML = '';
	const box = document.createElement('p');
	box.className = 'error';
	box.setAttribute('role', 'alert');
	box.textContent = message;
	el.append(box);
}

/* 확인 번호 보관. 시크릿 모드나 저장이 막힌 인앱 브라우저에서는 localStorage가 던진다.
 * 그때도 예약 자체는 이미 성공했으므로 화면이 깨지면 안 된다 — 메모리로 물러선다. */
let memoryToken = '';
const store = {
	get token() {
		try {
			return localStorage.getItem('coach2.token') || memoryToken;
		} catch {
			return memoryToken;
		}
	},
	set token(value) {
		memoryToken = value;
		try {
			localStorage.setItem('coach2.token', value);
		} catch {
			/* 저장은 못 해도 확인 번호는 화면에 그대로 표시된다 */
		}
	},
};
