package net.sf.robocode.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;

public class RunBattleRequest {
	RunBattleRequest() {
		robots = new ArrayList<>();
	}

	private ArrayList<String> robots;

	@JsonProperty("robots")
	public ArrayList<String> getRobots() {
		return robots;
	}

	@JsonProperty("robots")
	public void setRobots(ArrayList<String> robots) {
		this.robots = robots;
	}
}
