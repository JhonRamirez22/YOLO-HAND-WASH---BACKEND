package com.handwash.api.v1.dto;

/** forceLink replaces the Mac already linked to the station; without it the existing Mac is left alone. */
public record KeyLoginRequest(String key, Boolean linkStation, Boolean forceLink) {}
