package com.interview.coach2.reservation;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReservationService {

	private final BoothRepository booths;
	private final VisitorRepository visitors;
	private final ReservationRepository reservations;
	private final ApprovalRepository approvals;
	private final ReservationWriter writer;

	public ReservationService(BoothRepository booths, VisitorRepository visitors,
	                          ReservationRepository reservations, ApprovalRepository approvals,
	                          ReservationWriter writer) {
		this.booths = booths;
		this.visitors = visitors;
		this.reservations = reservations;
		this.approvals = approvals;
		this.writer = writer;
	}

	/** 예약 결과. 발급된 토큰을 함께 돌려준다(이후 조회·취소의 열쇠). */
	public record BookResult(Reservation reservation, String visitorToken) {
	}

	@Transactional(readOnly = true)
	public List<Booth> activeBooths() {
		return booths.findByActiveTrueOrderByBoothNoAsc();
	}

	@Transactional(readOnly = true)
	public Booth booth(Long boothId) {
		return booths.findById(boothId).orElseThrow(() -> notFound("부스를 찾을 수 없습니다"));
	}

	@Transactional(readOnly = true)
	public List<Instant> availableSlots(Long boothId) {
		return openSlots(boothId).stream().map(SlotSeats::startTime).toList();
	}

	/** 남은 좌석이 하나라도 있는 슬롯과 그 자리 수. */
	public record SlotSeats(Instant startTime, int remaining, int capacity) {
	}

	/**
	 * availableSlots는 '자리가 있나'만 답한다. 정원이 여럿인 부스에서는 한 명이 예약해도
	 * 그 답이 바뀌지 않아, 화면이 예약을 받은 티를 전혀 내지 못한다. 남은 수까지 돌려준다.
	 *
	 * 이미 지난 슬롯과 다 찬 슬롯은 여기서도 빼므로, availableSlots는 이 결과의 시각만
	 * 뽑으면 된다 — 두 곳에서 같은 규칙을 따로 쓰면 언젠가 서로 어긋난다.
	 */
	@Transactional(readOnly = true)
	public List<SlotSeats> openSlots(Long boothId) {
		Booth booth = activeBooth(boothId);
		// 슬롯마다 몇 명이 찼는지 센다. 정원이 1이면 예전처럼 '있으면 마감'과 같아진다.
		Map<Instant, Long> taken = reservations
			.findByBoothIdAndStatus(boothId, ReservationStatus.RESERVED)
			.stream()
			.collect(Collectors.groupingBy(Reservation::getStartTime, Collectors.counting()));

		Instant now = Instant.now();
		int capacity = booth.getCapacity();
		return Slots.forBooth(booth).stream()
			.filter(slot -> slot.isAfter(now))
			.map(slot -> new SlotSeats(slot,
				capacity - taken.getOrDefault(slot, 0L).intValue(), capacity))
			.filter(s -> s.remaining() > 0)
			.toList();
	}

	/**
	 * ⚠ 이 메서드에 @Transactional을 붙이지 말 것. 커넥션 풀이 데드락한다.
	 *
	 * 실제 INSERT는 ReservationWriter가 REQUIRES_NEW로 자기 트랜잭션에서 한다(제약 위반이
	 * 바깥을 오염시키지 않게 하려고). 여기에 바깥 트랜잭션까지 있으면 요청 하나가 커넥션을
	 * 동시에 두 개 쥐게 되고, HikariCP 기본 풀 10개에서는 열 요청이 각자 하나씩 잡은 채
	 * 두 번째를 기다리다 전원이 타임아웃한다.
	 *
	 * 실측: 정원 1인 슬롯에 40명이 동시에 예약하면 40건 전부
	 * CannotCreateTransactionException(500)으로 실패했다. ConcurrentBookingTest가 이걸 지킨다.
	 *
	 * 트랜잭션이 없어도 안전하다. 이 메서드의 조회들은 서로 원자적일 필요가 없고
	 * (사전검사는 원래 경합을 막지 못한다 — 진짜 방어선은 유니크 제약이다),
	 * 엔티티에 지연로딩 연관관계가 하나도 없어 세션 밖에서 필드를 읽어도 문제가 없다.
	 */
	public BookResult book(Long boothId, Instant startTime, String name, String rawPhone) {
		return book(boothId, startTime, name, rawPhone, null);
	}

	public BookResult book(Long boothId, Instant startTime, String name, String rawPhone,
	                       String approvalToken) {
		Booth booth = activeBooth(boothId);

		if (booth.isApprovalRequired()) {
			// 이름·연락처는 명단에 적힌 것을 쓴다. 화면이 보낸 값을 그대로 믿으면 남의 링크를
			// 받아 자기 번호로 그 자리를 가져갈 수 있다. 토큰이 곧 그 사람이다.
			Approval approval = approvalFor(approvalToken, boothId);
			name = approval.getName();
			rawPhone = approval.getPhone();
		}

		String phone = PhoneNumbers.normalize(rawPhone);
		if (phone == null) {
			throw badRequest("연락처를 확인해 주세요");
		}
		if (name == null || name.isBlank()) {
			throw badRequest("이름을 입력해 주세요");
		}
		// 클라이언트가 보낸 시각이 실제로 이 부스의 슬롯인지 확인한다.
		// 이 검사가 없으면 운영시간 밖이나 슬롯 경계에 걸치지 않는 임의 시각을 밀어넣을 수 있다.
		if (!Slots.forBooth(booth).contains(startTime)) {
			throw badRequest("예약할 수 없는 시간입니다");
		}
		if (!startTime.isAfter(Instant.now())) {
			throw badRequest("지난 시간은 예약할 수 없습니다");
		}

		Visitor visitor = findOrCreateVisitor(name, phone);

		// 아래 세 검사는 흔한 경우에 무엇이 문제인지 알려주기 위한 것이다.
		// 동시 요청은 이걸로 막지 못한다 — 진짜 방어선은 Reservation의 유니크 제약이다.
		if (reservations.countByBoothIdAndStartTimeAndStatus(
				boothId, startTime, ReservationStatus.RESERVED) >= booth.getCapacity()) {
			// 1:1이면 '이미 예약된', 그룹이면 '정원이 찬' 것이다. 사용자가 읽는 문장이므로 구분한다.
			throw conflict(booth.getCapacity() == 1
				? "이미 예약된 시간입니다"
				: "이 시간은 정원이 모두 찼습니다");
		}
		if (reservations.existsByVisitorIdAndStartTimeAndStatus(
				visitor.getId(), startTime, ReservationStatus.RESERVED)) {
			throw conflict("같은 시간에 다른 부스를 이미 예약하셨습니다");
		}
		if (reservations.existsByVisitorIdAndBoothIdAndStatus(
				visitor.getId(), boothId, ReservationStatus.RESERVED)) {
			throw conflict("이 부스는 이미 예약하셨습니다. 다른 시간으로 바꾸시려면 기존 예약을 취소해 주세요");
		}

		// 1번 좌석부터 넣어 본다. 이미 찬 좌석은 유니크 제약이 거절하므로 다음 번호로 넘어간다.
		// 좌석 문자열이 정원 개수만큼만 존재하니, 동시 요청이 몇 개든 정원을 넘길 수 없다.
		for (int seat = 1; seat <= booth.getCapacity(); seat++) {
			try {
				Reservation saved = writer.insert(new Reservation(
					boothId, visitor.getId(), startTime, booth.getSlotMinutes(), seat));
				return new BookResult(saved, visitor.getToken());
			} catch (DataIntegrityViolationException e) {
				// 이 좌석은 방금 다른 사람이 가져갔다. 다음 좌석을 시도한다.
				// visitorSlotKey·visitorBoothKey 위반이면 어느 좌석을 시도해도 똑같이 실패하고
				// 아래 conflict로 떨어진다. 제약 이름을 파싱해 구분하지는 않는다 — DB에 종속적이라
				// 깨지기 쉽고, 사용자가 할 행동은 어느 쪽이든 같다.
			}
		}
		// 위 검사와 INSERT 사이에 다른 요청들이 남은 좌석을 모두 가져간 경우.
		throw conflict("방금 다른 예약이 확정되었습니다. 다른 시간을 선택해 주세요");
	}

	@Transactional(readOnly = true)
	public List<Reservation> myReservations(String token) {
		return reservationsOf(visitorByToken(token).getId());
	}

	@Transactional(readOnly = true)
	public List<Reservation> reservationsOf(Long visitorId) {
		return reservations.findByVisitorIdOrderByStartTimeDesc(visitorId);
	}

	@Transactional
	public void cancel(Long reservationId, String token) {
		Visitor visitor = visitorByToken(token);
		Reservation reservation = reservations.findById(reservationId)
			.orElseThrow(() -> notFound("예약을 찾을 수 없습니다"));
		// 남의 예약이면 403이 아니라 404를 준다 — 토큰을 넣어보며 예약의 존재 여부를
		// 확인하는 것을 막는다.
		if (!reservation.isOwnedBy(visitor.getId())) {
			throw notFound("예약을 찾을 수 없습니다");
		}
		reservation.cancel();
	}

	/** 부스 담당자용. 토큰이 가리키는 부스와 그 부스의 유효한 예약만 돌려준다. */
	@Transactional(readOnly = true)
	public Booth boothByStaffToken(String staffToken) {
		if (staffToken == null || staffToken.isBlank()) {
			throw notFound("부스를 찾을 수 없습니다");
		}
		return booths.findByStaffToken(staffToken)
			.orElseThrow(() -> notFound("부스를 찾을 수 없습니다"));
	}

	@Transactional(readOnly = true)
	public List<Reservation> reservationsFor(Long boothId) {
		return reservations.findByBoothIdAndStatus(boothId, ReservationStatus.RESERVED).stream()
			.sorted(java.util.Comparator.comparing(Reservation::getStartTime))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<Reservation> reservationsOn(LocalDate date) {
		return reservations.findByStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
			ReservationStatus.RESERVED, Slots.startOfDay(date), Slots.endOfDay(date));
	}

	@Transactional(readOnly = true)
	public Visitor visitorByToken(String token) {
		if (token == null || token.isBlank()) {
			throw notFound("예약을 찾을 수 없습니다");
		}
		return visitors.findByToken(token).orElseThrow(() -> notFound("예약을 찾을 수 없습니다"));
	}

	/**
	 * 이름 + 연락처로 사람을 찾는다. 로그인이 없으므로 이 조합이 본인 확인의 전부다.
	 *
	 * 번호가 없을 때와 이름이 어긋날 때를 같은 404·같은 문구로 돌려준다. 메시지를 나누면
	 * "이 번호가 예약자인가"를 확인하는 오라클이 되어, 번호만 아는 사람에게 참가 여부와
	 * 지원 기업이 새어 나간다.
	 *
	 * ponytail: 호출 횟수 제한이 없다. 이름까지 맞아야 하므로 무작위 대입은 무의미하지만,
	 * 명단이 있는 표적 열거까지 막으려면 IP·번호 단위 제한이 필요하다. nginx의 limit_req는
	 * limit_req_zone을 http 블록에 둬야 해서 eastAI 설정을 건드리게 된다 — 넣는다면 앱 쪽에.
	 */
	@Transactional(readOnly = true)
	public Visitor visitorByNameAndPhone(String name, String rawPhone) {
		String phone = PhoneNumbers.normalize(rawPhone);
		if (phone == null) {
			throw badRequest("연락처를 확인해 주세요");
		}
		if (name == null || name.isBlank()) {
			throw badRequest("이름을 입력해 주세요");
		}
		// 두 실패가 한 문구를 공유하는 것이 위 오라클 방어의 전부다. 안내를 덧붙이더라도
		// 갈래마다 다른 말을 하면 안 된다.
		return visitors.findByPhone(phone)
			.filter(v -> sameName(v.getName(), name))
			.orElseThrow(() -> notFound(
				"예약을 찾을 수 없습니다. 예약할 때 적으신 이름과 연락처가 맞는지 확인해 주세요"));
	}

	/**
	 * 공백과 대소문자는 무시한다 — "홍 길동"과 "홍길동"이 갈리면 본인이 자기 예약을 못 찾는다.
	 *
	 * 비교 대상은 저장된 이름이다. findOrCreateVisitor가 번호로만 사람을 찾으므로,
	 * 두 번째 예약에서 이름을 다르게 적어도 Visitor의 이름은 첫 예약 때 것으로 남는다.
	 */
	private static boolean sameName(String stored, String given) {
		return stored.replaceAll("\\s", "").equalsIgnoreCase(given.replaceAll("\\s", ""));
	}

	/**
	 * 개별 안내 링크가 가리키는 합격자. 링크를 연 화면이 자기 이름과 어느 기업인지 확인하는 데 쓴다.
	 *
	 * 토큰이 틀렸을 때 404다. 403으로 답하면 '있긴 한데 권한이 없다'는 뜻이 되어, 토큰을
	 * 넣어보며 유효한 링크를 찾는 데 단서를 준다.
	 */
	@Transactional(readOnly = true)
	public Approval approvalByToken(String token) {
		if (token == null || token.isBlank()) {
			throw notFound("링크를 확인할 수 없습니다");
		}
		return approvals.findByToken(token)
			.orElseThrow(() -> notFound("링크를 확인할 수 없습니다"));
	}

	/**
	 * 예약 시점의 검사. 여기서는 403이다 — 예약하려는 부스가 무엇인지는 이미 공개된 정보이고,
	 * 사용자에게 '왜 안 되는지'를 알려주지 않으면 링크를 받고도 헤맨다.
	 */
	private Approval approvalFor(String token, Long boothId) {
		if (token == null || token.isBlank()) {
			throw forbidden("서류 합격자에게 보내드린 개별 링크로만 예약할 수 있습니다");
		}
		return approvals.findByToken(token)
			.filter(a -> a.isFor(boothId))
			.orElseThrow(() -> forbidden("이 링크로는 이 면접을 예약할 수 없습니다. 안내받은 링크를 다시 확인해 주세요"));
	}

	private Visitor findOrCreateVisitor(String name, String phone) {
		return visitors.findByPhone(phone).orElseGet(() -> {
			try {
				return writer.insertVisitor(new Visitor(name, phone));
			} catch (DataIntegrityViolationException e) {
				// 같은 번호로 동시에 첫 예약이 들어온 경우 — 먼저 들어간 쪽을 쓴다.
				return visitors.findByPhone(phone).orElseThrow(() -> e);
			}
		});
	}

	private Booth activeBooth(Long boothId) {
		Booth booth = booth(boothId);
		if (!booth.isActive()) {
			throw notFound("부스를 찾을 수 없습니다");
		}
		return booth;
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

	private static ResponseStatusException forbidden(String message) {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}
}
