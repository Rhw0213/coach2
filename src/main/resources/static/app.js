/* 3개 페이지가 공유하는 최소 헬퍼. 프레임워크도 빌드 단계도 없다. */

/* API는 이 페이지와 같은 디렉터리 아래에 있다.
 * context-path(/reserve)를 하드코딩하지 않으므로 서브도메인으로 옮겨도 그대로 동작한다. */
const BASE = location.pathname.replace(/[^/]*$/, '');

const KST = 'Asia/Seoul';

async function api(path, options = {}) {
	const res = await fetch(BASE + path, {
		headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
		...options,
	});
	if (res.status === 204 || res.headers.get('content-length') === '0') return null;

	const text = await res.text();
	const data = text ? JSON.parse(text) : null;
	if (!res.ok) {
		// ResponseStatusException은 ProblemDetail로 직렬화되고 사용자용 메시지는 detail에 담긴다.
		throw new Error(data?.detail || data?.message || `요청에 실패했습니다 (${res.status})`);
	}
	return data;
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
	const now = new Date();
	const kst = new Date(now.toLocaleString('en-US', { timeZone: KST }));
	kst.setDate(kst.getDate() + offset);
	return `${kst.getFullYear()}-${String(kst.getMonth() + 1).padStart(2, '0')}-${String(kst.getDate()).padStart(2, '0')}`;
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

function showError(el, message) {
	el.innerHTML = '';
	const box = document.createElement('p');
	box.className = 'error';
	box.textContent = message;
	el.append(box);
}

const store = {
	get token() { return localStorage.getItem('coach2.token') || ''; },
	set token(v) { localStorage.setItem('coach2.token', v); },
};
