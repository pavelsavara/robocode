package net.sf.robocode.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import robocode.BattleResults;

public class RunBattleResponse {
	private BattleResults[] results;

	@JsonProperty("robots")
	public BattleResults[] getResults() {
		return results;
	}

	@JsonProperty("robots")
	public void setResults(BattleResults[] results) {
		this.results = results;
	}
}
