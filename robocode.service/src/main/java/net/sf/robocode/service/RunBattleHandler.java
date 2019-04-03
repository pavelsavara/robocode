package net.sf.robocode.service;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import net.sf.robocode.service.dto.ApiGatewayResponse;
import net.sf.robocode.service.dto.Response;
import net.sf.robocode.service.dto.RunBattleRequest;
import net.sf.robocode.service.dto.RunBattleResponse;
import net.sf.robocode.service.utils.RobotDownloader;
import org.apache.log4j.Logger;
import robocode.control.*;
import robocode.control.events.*;

import java.util.Collections;

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
			ex.printStackTrace();

			// send the error response back
			Response responseBody = new Response("Error in running battle");
			return ApiGatewayResponse.builder()
					.setStatusCode(500)
					.setObjectBody(responseBody)
					.setHeaders(Collections.singletonMap("X-Powered-By", "AWS Lambda & Robocode"))
					.build();
		}
	}

	class Listener extends BattleAdaptor {
		public BattleCompletedEvent finished;

		@Override
		public void onBattleCompleted(BattleCompletedEvent event) {
			finished = event;
		}

	}

	public RunBattleResponse runBattle(RunBattleRequest request) {
		RunBattleResponse response = new RunBattleResponse();

		int rounds = 1;
		int width = 800;
		int height = 600;

		RobocodeEngine engine = new RobocodeEngine();
		engine.setVisible(false);

		String names = RobotDownloader.downloadRobots(request.getRobots(), RobocodeEngine.getRobotsDir());
		RobotSpecification[] robots = engine.getLocalRepository();

		BattlefieldSpecification field = new BattlefieldSpecification(width, height);
		BattleSpecification battle = new BattleSpecification(rounds, field, robots);
		Listener listener = new Listener();
		engine.addBattleListener(listener);
		engine.runBattle(new BattleSpecification(10, new BattlefieldSpecification(), robots), true);

		response.setResults(listener.finished.getIndexedResults());

		return response;
	}
}
