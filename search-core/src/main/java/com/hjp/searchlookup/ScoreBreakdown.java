package com.hjp.searchlookup;

public final class ScoreBreakdown {
    public final double keywordScore;
    public final double semanticScore;
    public final double synonymScore;
    public final double rerankScore;
    public final double finalScore;

    public ScoreBreakdown(double keywordScore, double semanticScore, double synonymScore,
            double rerankScore, double finalScore) {
        this.keywordScore = keywordScore;
        this.semanticScore = semanticScore;
        this.synonymScore = synonymScore;
        this.rerankScore = rerankScore;
        this.finalScore = finalScore;
    }

    public static ScoreBreakdown keywordOnly(double keywordScore) {
        return new ScoreBreakdown(keywordScore, 0.0, 0.0, 0.0, keywordScore);
    }
}
