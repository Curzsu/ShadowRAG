package com.yizhaoqi.smartpai.service;

public class ContextWindowExceededException extends RuntimeException {

    private final int requiredTokens;
    private final int availableTokens;

    public ContextWindowExceededException(int requiredTokens, int availableTokens) {
        super("Required context tokens " + requiredTokens + " exceed available budget " + availableTokens);
        this.requiredTokens = requiredTokens;
        this.availableTokens = availableTokens;
    }

    public int getRequiredTokens() {
        return requiredTokens;
    }

    public int getAvailableTokens() {
        return availableTokens;
    }
}
