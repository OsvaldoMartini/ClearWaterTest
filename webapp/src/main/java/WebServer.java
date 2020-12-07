import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

@Configuration
@PropertySource("classpath:/application.properties")
public class WebServer {
	private final static Logger logger = Logger.getLogger(WebServer.class);
	private static final int NUMBER_OF_THREADS = 8;

	private static final String READ_LOGS = "/logs";
	private static final String STATUS_ENDPOINT = "/status";
	private static final String HOME_PAGE_ENDPOINT = "/";
	private static final String DEFAULT_LOG_PATH = "/tmp/target/classes/logs";
	private static final int TIME_SECONDS = 5;

	private static final String HTML_PAGE = "index.html";

	private final int port;
	private HttpServer server;
	private final String serverName;

	@Value("${logsPath}")
	String logsPath;

	@Value("${timeBackInSeconds}")
	String timeBackInSeconds;

	/**
	 * BaseLog enum.
	 */
	private enum BaseLog {
		WARNING, INFO, ERROR;

		/**
		 * Pick a random value of the Log enum.
		 * 
		 * @return a random Log.
		 */
		public static BaseLog getRandomLog() {
			Random random = new Random();
			return values()[random.nextInt(values().length)];
		}
	}

	public WebServer(int port, String serverName) {
		this.port = port;
		this.serverName = serverName;
	}

	public void startServer() {
		try {
			this.server = HttpServer.create(new InetSocketAddress(port), 0);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		int maxPoolSize = 1000;
		int keepAliveTime = 120;

		BlockingQueue<Runnable> workQueue = new SynchronousQueue<Runnable>();

		ThreadPoolExecutor pool = new ThreadPoolExecutor(NUMBER_OF_THREADS, maxPoolSize, keepAliveTime,
				TimeUnit.SECONDS, workQueue);

		server.createContext(STATUS_ENDPOINT, this::handleStatusCheckRequest);
		server.createContext(HOME_PAGE_ENDPOINT, exchange -> {
			try {
				handleHomePageRequest(exchange);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});

		int timeBack = TIME_SECONDS;
		if (timeBackInSeconds != null) {
			timeBack = Integer.parseInt(timeBackInSeconds);
		}

		server.createContext(READ_LOGS, new LogsReader(logsPath, timeBack));

		server.setExecutor(Executors.newFixedThreadPool(8));
		System.out.println(String.format("Started server %s on port %d ", serverName, port));

		server.setExecutor(pool);
		server.start();
	}

	private void handleHomePageRequest(HttpExchange exchange) throws IOException, InterruptedException {
		if (!exchange.getRequestMethod().equalsIgnoreCase("get")) {
			exchange.close();
			return;
		}

		if (BaseLog.getRandomLog().equals(BaseLog.WARNING)) {
			logger.warn("Some warning message");
		} else if (BaseLog.getRandomLog().equals(BaseLog.INFO)) {
			logger.info("Some info message");
		} else if (BaseLog.getRandomLog().equals(BaseLog.ERROR)) {
			logger.error("Some error message");
		}

		System.out.println(String.format("%s received a request", this.serverName));
		exchange.getResponseHeaders().add("Content-Type", "text/html");
		exchange.getResponseHeaders().add("Cache-Control", "no-cache");

		byte[] response = loadHtml(HTML_PAGE);

		sendResponse(response, exchange);
	}

	/**
	 * Loads the HTML page to be fetched to the web browser
	 *
	 * @param htmlFilePath - The relative path to the html file
	 * @throws IOException
	 */
	private byte[] loadHtml(String htmlFilePath) throws IOException {
		InputStream htmlInputStream = getClass().getResourceAsStream(htmlFilePath);
		if (htmlInputStream == null) {
			return new byte[] {};
		}

		Document document = Jsoup.parse(htmlInputStream, "UTF-8", "");

		String modifiedHtml = modifyHtmlDocument(document);
		return modifiedHtml.getBytes();
	}

	/**
	 * Fills the server's name and local time in theHTML document
	 *
	 * @param document - original HTML document
	 */
	private String modifyHtmlDocument(Document document) {
		Element serverNameElement = document.selectFirst("#server_name");
		serverNameElement.appendText(serverName);
		return document.toString();
	}

	private void handleStatusCheckRequest(HttpExchange exchange) throws IOException {
		if (!exchange.getRequestMethod().equalsIgnoreCase("get")) {
			exchange.close();
			return;
		}

		System.out.println("Received a health check");
		String responseMessage = "Server is alive\n";
		sendResponse(responseMessage.getBytes(), exchange);
	}

	private void sendResponse(byte[] responseBytes, HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(200, responseBytes.length);
		OutputStream outputStream = exchange.getResponseBody();
		outputStream.write(responseBytes);
		outputStream.flush();
		outputStream.close();
	}

	private static class LogsReader implements HttpHandler {
		private String logsPath;
		private int timeBack;

		public LogsReader(String logsPath, int timeBack) {
			this.logsPath = logsPath;
			this.timeBack = timeBack;

		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!exchange.getRequestMethod().equalsIgnoreCase("get")) {
				exchange.close();
				return;
			}
			
			int warns = 0, infos = 0, errors = 0;
			try {

				if (BaseLog.getRandomLog().equals(BaseLog.WARNING)) {
					logger.warn("Some warning message");
				} else if (BaseLog.getRandomLog().equals(BaseLog.INFO)) {
					logger.info("Some info message");
				} else if (BaseLog.getRandomLog().equals(BaseLog.ERROR)) {
					logger.error("Some error message");
				}

				if (logsPath == null) {
					logsPath = DEFAULT_LOG_PATH;
				}

				timeBack = 5;
				
				File folder = new File(logsPath);
				
				File[] listOfFiles = folder.listFiles();

				// create an LocalDateTime object
				LocalDateTime ltNow = LocalDateTime.now().minusSeconds(timeBack);
			

				for (File file : listOfFiles) {
					try (BufferedReader br = new BufferedReader(new FileReader(file))) {
						String lineOut;
						String[] splitLine;

						while ((lineOut = br.readLine()) != null) {
							// process the line
							splitLine = lineOut.split(" ");

							LocalDateTime dateTime = LocalDateTime.parse(splitLine[0] + "T" + splitLine[1]);
							long seconds = ChronoUnit.SECONDS.between(ltNow, dateTime);

							if (seconds <= 5) {

								if (splitLine[2].equals("WARN")) {
									warns++;
								} else if (splitLine[2].equals("INFO")) {
									infos++;
								} else if (splitLine[2].equals("ERROR")) {
									errors++;
								}
							}

						}

					} catch (FileNotFoundException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			String response = "{\n" + "	\"info\": \"" + infos + "\",\n" + "	\"warning\":\"" + warns + "\",\n"
					+ "    \"error\":\"" + errors + "\"\n" + "}";
			exchange.getResponseHeaders().set("Content-Type", "appication/json");
			exchange.getResponseHeaders().set("Return_Code", "200");
			exchange.sendResponseHeaders(200, response.length());
			OutputStream outputStream = exchange.getResponseBody();
			outputStream.write(response.getBytes());
			outputStream.close();
		}

	}

}
