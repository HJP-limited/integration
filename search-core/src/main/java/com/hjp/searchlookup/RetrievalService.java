package com.hjp.searchlookup;
public interface RetrievalService { RetrievalResponse retrieve(String query, int topK); BusinessCard getCard(String cardId); }
