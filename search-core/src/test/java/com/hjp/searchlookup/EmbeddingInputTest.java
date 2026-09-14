package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class EmbeddingInputTest {
    @Test public void matchesPrecomputeScriptFormat() {
        BusinessCard card = new BusinessCard(
                "T001", "손다은", "Daeun Son", "(주) 블루오션컨설팅", "AI 개발자",
                "데이터사이언스팀", "it", "전라남도 순천시", "010-3000-6000",
                "daeun@example.com", "전라남도 순천시 월드컵로 11",
                "기술교류회에서 명함 교환", Arrays.asList("인공지능", "머신러닝", "모델학습"));

        assertEquals(
                "손다은, Daeun Son, (주) 블루오션컨설팅, AI 개발자, 데이터사이언스팀, "
                        + "it, 전라남도 순천시, 기술교류회에서 명함 교환, 인공지능, 머신러닝, 모델학습",
                card.searchableText());
    }
}
