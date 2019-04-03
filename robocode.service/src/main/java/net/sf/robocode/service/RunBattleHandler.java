package net.sf.robocode.service;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import net.sf.robocode.service.dto.ApiGatewayResponse;
import net.sf.robocode.service.dto.Response;
import net.sf.robocode.service.dto.RunBattleRequest;
import net.sf.robocode.service.dto.RunBattleResponse;
import org.apache.log4j.Logger;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import robocode.control.*;
import robocode.control.events.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.asynchttpclient.Dsl.asyncHttpClient;

public class RunBattleHandler implements RequestHandler<RunBattleRequest, ApiGatewayResponse> {

	private final Logger logger = Logger.getLogger(this.getClass());

	@Override
	public ApiGatewayResponse handleRequest(RunBattleRequest request, Context context) {

		try {
			RunBattleResponse response = runBattle(request);

			// send the response back
			return ApiGatewayResponse.builder()
					.setStatusCode(200)
					.setObjectBody(response)
					.setHeaders(Collections.singletonMap("X-Powered-By", "AWS Lambda & Robocode"))
					.build();

		} catch (Exception ex) {
			logger.error("Error in saving product: ", ex);

			// send the error response back
			Response responseBody = new Response("Error in running battle");
			return ApiGatewayResponse.builder()
					.setStatusCode(500)
					.setObjectBody(responseBody)
					.setHeaders(Collections.singletonMap("X-Powered-By", "AWS Lambda & Robocode"))
					.build();
		}
	}

	public RunBattleResponse runBattle(RunBattleRequest request) {


		File robotsDir = RobocodeEngine.getRobotsDir();
		logger.info("RobotsDir:" + robotsDir.getAbsolutePath());

		String names = downloadRobots(request.getRobots(), robotsDir);


		RobocodeEngine engine = new RobocodeEngine();
		Listener listener = new Listener();
		engine.addBattleListener(listener);

		RobotSpecification[] robots = engine.getLocalRepository();

		int rounds = 1;
		int width = 800;
		int height = 600;
		BattlefieldSpecification field = new BattlefieldSpecification(width, height);
		BattleSpecification battle = new BattleSpecification(rounds, field, robots);
		engine.runBattle(battle, true);

		RunBattleResponse response = new RunBattleResponse();
		response.setResults(listener.finished.getIndexedResults());

		return response;
	}

	private String downloadRobots(ArrayList<String> robots, File robotDir) {
		if (!robotDir.exists()) {
			robotDir.mkdir();
		}
		AsyncHttpClient client = asyncHttpClient();
		return robots.parallelStream()
				.map(url -> {
					try {
						int i = url.lastIndexOf('/');
						if (i == -1) {
							return new Object[][]{null, null};
						}

						File robot = Paths.get(robotDir.getAbsolutePath(), url.substring(i)).toFile();
						if (robot.exists()) {
							return new Object[][]{null, null};
						}

						return new Object[]{robot, url};
					} catch (Exception e) {
						return new Object[]{null, null};
					}
				})
				.filter(r -> r[0] != null && r[1] != null)
				.map(r -> new Object[]{r[0], client.prepareGet((String) r[1]).execute()})
				.map(f -> {
					try {
						ListenableFuture<org.asynchttpclient.Response> future = (ListenableFuture<org.asynchttpclient.Response>) f[1];
						File robot = (File) f[0];
						org.asynchttpclient.Response response = future.get(3000, TimeUnit.MILLISECONDS);
						java.nio.file.Files.copy(
								response.getResponseBodyAsStream(),
								robot.toPath(),
								StandardCopyOption.REPLACE_EXISTING);

						String name = robot.getName();
						int i = name.indexOf('.');
						if (i == -1) {
							return name;
						}
						return name.substring(0, i);
					} catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
						return "null";
					}
				})
				.collect(Collectors.joining(","));
	}


}

class Listener extends BattleAdaptor {
	public BattleCompletedEvent finished;
	private final Logger logger = Logger.getLogger(this.getClass());

	@Override
	public void onBattleCompleted(BattleCompletedEvent event) {
		finished = event;
	}

	@Override
	public void onBattleMessage(BattleMessageEvent event) {
		logger.info(event.getMessage());
	}
}

