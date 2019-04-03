package net.sf.robocode.service;

import net.sf.robocode.service.dto.ApiGatewayResponse;
import net.sf.robocode.service.dto.RunBattleRequest;
import net.sf.robocode.service.dto.RunBattleResponse;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

public class RunBattleHandlerTest {
	@Test
	public void initialize() {
		RunBattleHandler handler = new RunBattleHandler();
		RunBattleRequest request = new RunBattleRequest();
		ArrayList<String> robots = new ArrayList<>();
		robots.add("http://robocode-archive.strangeautomata.com/robots/slugzilla.Basilisk_2.10.jar");
		robots.add("http://robocode-archive.strangeautomata.com/robots/voidious.Diamond_1.8.28.jar");
		request.setRobots(robots);

		System.out.println("run");
		RunBattleResponse response = handler.runBattle(request);
		System.out.println("0 " + response.getResults()[0].getScore());
		System.out.println("1 " + response.getResults()[1].getScore());
	}

	@Test
	public void json() {
		RunBattleHandler handler = new RunBattleHandler();

		HashMap<String, Object> input = new HashMap<>();
		input.put("body", "{\n" +
				"  \"robots\": [\n" +
				"    \"http://robocode-archive.strangeautomata.com/robots/slugzilla.Basilisk_2.10.jar\",\n" +
				"    \"http://robocode-archive.strangeautomata.com/robots/voidious.Diamond_1.8.28.jar\"\n" +
				"  ]\n" +
				"}");
		ApiGatewayResponse apiGatewayResponse = handler.handleRequest(input, null);

		System.out.println(apiGatewayResponse.getBody());

	}
}
