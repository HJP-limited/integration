package com.example.hjp.eval.clock

/**
 * 평가가 "지금"이라고 여기는 시각.
 *
 * Agent_0910 의 테스트 하네스가 이 타입을 쓰는데 **그 브랜치에 구현이 커밋돼 있지 않다**
 * (`com.example.hjp.eval` 패키지 전체가 없다). 하네스가 요구하는 표면은 `millis` 하나뿐이라
 * 여기서 최소한으로 되살린다.
 *
 * 고정 시각을 주는 게 핵심이다 — "내일 2시" 같은 상대 날짜가 들어간 시나리오는 기준 시각이
 * 흔들리면 실행한 날에 따라 결과가 달라져 재현되지 않는다.
 */
class EvaluationClock private constructor(
    /** epoch 밀리초를 돌려준다. 도구(get_current_datetime)와 세션이 같은 값을 본다. */
    val millis: () -> Long,
) {
    companion object {
        fun system(): EvaluationClock = EvaluationClock { System.currentTimeMillis() }

        /** 지정한 시각에 멈춘 시계. 같은 데이터셋이 어느 날 돌려도 같은 결과를 낸다. */
        fun fixed(epochMillis: Long): EvaluationClock = EvaluationClock { epochMillis }
    }
}
