package com.openrealm.game.state;

import com.openrealm.account.dto.PlayerAccountDto;

/** Outcome of a completed {@link CharacterSelectReturn} worker: the refreshed account, or an error. */
public final class CharacterSelectReturnResult {

    private final PlayerAccountDto account;
    private final String error;

    CharacterSelectReturnResult(PlayerAccountDto account, String error) {
        this.account = account;
        this.error = error;
    }

    public PlayerAccountDto getAccount() {
        return this.account;
    }

    public String getError() {
        return this.error;
    }
}
