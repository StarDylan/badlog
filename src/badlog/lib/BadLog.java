package badlog.lib;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.Supplier;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class BadLog {

	/**
	 * The unit to use when the data does not have a unit.
	 */
	public static final String UNITLESS = "ul";
	public static final String DEFAULT_DATA = Double.toString(-1.0);

	private static final Function<Double, String> DOUBLE_TO_STRING_FUNC = (d) -> String.format("%.5g", d);

	private static Optional<BadLog> instance = Optional.empty();

	private boolean registerMode;

	private List<NamespaceObject> namespace;
	private HashMap<String, Optional<String>> publishedData;
	private List<Topic> topics;
	
	private FileWriter fileWriter;

	private BadLog(String path) {
		registerMode = true;
		namespace = new ArrayList<>();
		topics = new ArrayList<>();
		publishedData = new HashMap<>();
		try {
			fileWriter = new FileWriter(path);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Initializes BadLog.
	 * @param path of bag file
	 * @return the instance of BadLog
	 * @throws RuntimeException if already initialized
	 */
	public static BadLog init(String path) {
		if (instance.isPresent())
			throw new RuntimeException();

		BadLog badLog = new BadLog(path);
		instance = Optional.of(badLog);

		return badLog;
	}

	/**
	 * Creates a topic that logs Strings.
	 * @param name
	 * @param unit
	 * @param supplier the function to be called to return the logged data
	 * @param attrs array of topic attributes
	 */
	public static void createTopicStr(String name, String unit, Supplier<String> supplier, String... attrs) {
		if (!instance.get().registerMode)
			throw new InvalidModeException();
		if (isInNamespace(name))
			throw new DuplicateNameException();
		
		instance.get().checkName(name);

		QueriedTopic topic = new QueriedTopic(name, unit, supplier, attrs);
		instance.get().namespace.add(topic);
		instance.get().topics.add(topic);
	}

	/**
	 * Creates a topic that logs doubles.
	 * 
	 * Doubles are converted to strings based on the doubleToString function.
	 * 
	 * @param name
	 * @param unit
	 * @param supplier the function to be called to return the logged data
	 * @param attrs array of topic attributes
	 */
	public static void createTopic(String name, String unit, Supplier<Double> supplier, String... attrs) {
		createTopicStr(name, unit, () -> DOUBLE_TO_STRING_FUNC.apply(supplier.get()), attrs);
	}

	/**
	 * Creates a subscribed topic.
	 * @param name
	 * @param unit
	 * @param inferMode the method to use if data has not been published
	 * @param attrs array of topic attributes
	 */
	public static void createTopicSubscriber(String name, String unit, DataInferMode inferMode, String... attrs) {
		if (!instance.get().registerMode)
			throw new InvalidModeException();
		if (isInNamespace(name))
			throw new DuplicateNameException();
		
		instance.get().checkName(name);

		instance.get().publishedData.put(name, Optional.empty());
		SubscribedTopic topic = new SubscribedTopic(name, unit, inferMode, attrs);
		instance.get().namespace.add(topic);
		instance.get().topics.add(topic);
	}

	/**
	 * Creates a named value.
	 * @param name
	 * @param value
	 */
	public static void createValue(String name, String value) {
		if (!instance.get().registerMode)
			throw new InvalidModeException();
		if (isInNamespace(name))
			throw new DuplicateNameException();
		
		instance.get().checkName(name);

		instance.get().namespace.add(new Value(name, value));
	}

	/**
	 * Publish a string to a topic.
	 * @param name
	 * @param value
	 */
	public static void publish(String name, String value) {
		BadLog tmp = instance.get();
		if (tmp.registerMode)
			throw new InvalidModeException();
		tmp.recievePublishedData(name, value);
	}

	/**
	 * Publish a double to a topic.
	 * @param name
	 * @param value
	 */
	public static void publish(String name, double value) {
		publish(name, DOUBLE_TO_STRING_FUNC.apply(value));
	}

	/**
	 * Closes the logger for any new values or topics.
	 * Prints bag file headers.
	 */
	public void finalize() {
		if (!registerMode)
			throw new InvalidModeException();
		registerMode = false;

		String jsonHeader = genJsonHeader();

		// CSV Header
		StringJoiner joiner = new StringJoiner(",");
		topics.stream().map(Topic::getName).forEach((n) -> joiner.add(n));
		String header = joiner.toString();

		writeLine(jsonHeader);
		writeLine(header);
		
		try {
			fileWriter.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	private String genJsonHeader() {
		JSONObject jsonRoot = new JSONObject();

		JSONArray jsonTopics = new JSONArray();
		for (Topic t : topics) {
			JSONObject topic = new JSONObject();
			topic.put("name", t.getName());
			topic.put("unit", t.getUnit());
			JSONArray attrs = new JSONArray();
			Arrays.stream(t.getAttributes()).forEach((a) -> attrs.add(a));
			topic.put("attrs", attrs);
			jsonTopics.add(topic);
		}
		
		jsonRoot.put("topics", jsonTopics);

		JSONArray jsonValues = new JSONArray();
		namespace.stream().filter((o) -> o instanceof Value).map((v) -> (Value) v).forEach((v) -> {
			JSONObject value = new JSONObject();
			value.put("name", v.getName());
			value.put("value", v.getValue());
			jsonValues.add(value);
		});

		jsonRoot.put("values", jsonValues);

		return jsonRoot.toJSONString();
	}

	/**
	 * Query all queried topics and process published data.
	 * 
	 * This must be called before each call to log
	 */
	public void updateTopics() {
		if (registerMode)
			throw new InvalidModeException();

		topics.stream().filter((o) -> o instanceof QueriedTopic).map((o) -> (QueriedTopic) o)
				.forEach(QueriedTopic::refreshValue);

		topics.stream().filter((o) -> o instanceof SubscribedTopic).map((o) -> (SubscribedTopic) o)
				.forEach((t) -> t.handlePublishedData(publishedData.get(t.getName())));

		publishedData.replaceAll((k, v) -> Optional.empty());
	}

	/**
	 * Write the values of each topic to the bag file.
	 */
	public void log() {
		if (registerMode)
			throw new InvalidModeException();

		StringJoiner joiner = new StringJoiner(",");
		topics.stream().map(Topic::getValue).forEach((v) -> joiner.add(v));
		String line = joiner.toString();
		
		writeLine(line);
	}

	private static boolean isInNamespace(String name) {
		return instance.get().namespace.stream().anyMatch((o) -> o.getName().equals(name));
	}

	private void recievePublishedData(String name, String value) {
		if (publishedData.get(name) == null)
			throw new NullPointerException();

		publishedData.put(name, Optional.of(value));
	}
	
	private void checkName(String name) {
		for(char c : name.toCharArray()) {
			if(Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '/')
				continue;
			
			// Don't crash or throw exception, probably won't cause errors
			System.out.println("Invalid character " + c + " in name " + name);
			return;
		}
	}
	
	private void writeLine(String line) {
		try {
			fileWriter.write(line + System.lineSeparator());
			fileWriter.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
