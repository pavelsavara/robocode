package net.sf.robocode.service.utils;

import org.asynchttpclient.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.asynchttpclient.Dsl.*;

public class RobotDownloader {
	public static String downloadRobots(ArrayList<String> robots, File robotDir) {
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
						ListenableFuture<Response> future = (ListenableFuture<Response>) f[1];
						File robot = (File) f[0];
						Response response = future.get(3000, TimeUnit.MILLISECONDS);
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
