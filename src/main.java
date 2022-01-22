//DEPS com.microsoft.playwright:playwright:1.18.0
//FILES 5letterwords.txt
//JAVA 17

package pw.dasbrain.wordle;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

@FunctionalInterface
interface WordSelector {
	String selectWord(List<String> words);
}

public class main {

	public static void main(String[] args) throws IOException {
		System.out.println("Loading words...");
		List<String> words = loadWords();
		System.out.printf("%d words loaded\n", words.size());

		var game = new GameState(words, new WordSelector() {

			private final Random random = new Random();

			int selection = 0;
			@Override
			public String selectWord(List<String> words) {
				selection++;
				if(selection==1) {
					return "soare"; // https://bert.org/2021/11/24/the-best-starting-word-in-wordle/
				} else {
					return words.get(random.nextInt(words.size()));
				}
			}
		});
		
		try (Wordle wordle = new Wordle()) {
			try {
				wordle.init();
				for (int i = 0; i < 6; i++) {
					String word;
					WordTester.CharResult[] result;
					do {
						word = game.nextWord();
						result = wordle.typeWord(word);
						if (result == null) {
							System.out.printf(word + " not in Wordle database\n");
							game.remove(word);
						}
					} while (result == null);
					if (Arrays.stream(result)
							.allMatch(r -> r == WordTester.CharResult.CORRECT)) {
						System.out.println("Correct word is " + word);
						var filename = word + DateTimeFormatter.ofPattern("ddMMyyyyhhmmss").format(LocalDateTime.now()) +".png";
						wordle.share(filename);
						System.out.println("screenshot saved to " + filename);
						break;
					}
					game.filter(word, result);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			//wordle.waitForClose();
		}
	}
	
	private static List<String> loadWords() throws IOException {
		try (var is = main.class.getResourceAsStream("/5letterwords.txt")) {
			assert is != null;
			try (var isr = new InputStreamReader(is, StandardCharsets.UTF_8);
				 var br = new BufferedReader(isr)) {
				return br.lines().filter(s -> s.length() == 5)
						.map(String::toLowerCase).toList();
			}
		}
	}

}

class GameState {

	private List<String> words;
	private final WordSelector selector;

	public GameState(List<String> words, WordSelector selector) {
		this.words = words;
		this.selector = selector;
	}

	public String nextWord() {
		return selector.selectWord(words);
	}

	public List<String> words() {
		return words;
	}

	// reduce list of words to what matches the hints.
	public void filter(String word, WordTester.CharResult... result) {
		words = words.stream().filter(WordTester.makeFilter(word, result)).toList();
	}

	public void remove(String word) {
		words = words.stream().filter(s -> !s.equals(word)).toList();
	}
}

class Wordle implements AutoCloseable {

	Playwright playwright;
	Browser browser;
	Page page;
	int row = 1;

	public void init() {
		System.out.println("Loading Wordle in browser...");
		playwright = Playwright.create();
		browser = playwright.firefox()
				.launch(new BrowserType.LaunchOptions().setHeadless(false));
		page = browser.newPage();
		page.navigate("https://www.powerlanguage.co.uk/wordle/");
		page.click("game-icon[icon='close']");
	}

	public WordTester.CharResult[] typeWord(String word) {
		System.out.println("typing word \"" + word + "\"");
		for (char c : word.toCharArray()) {
			page.click("button[data-key=" + c + "]");
		}
		page.click("button[data-key=↵]");

		var elements = page.querySelectorAll("#board game-row:nth-child(" + row + ") game-tile");
		var result = elements
				.stream().map(e -> e.getAttribute("evaluation")).toList();
		if (result.contains(null)) {
			for (int i = 0; i < 5; i++) {
				page.click("button[data-key=←]");
			}
			page.waitForTimeout(1000);
			return null;
		}
		row++;
		page.waitForTimeout(2500);
		return result.stream().map(s -> WordTester.CharResult.valueOf(s.toUpperCase()))
				.toArray(WordTester.CharResult[]::new);
	}

	public void share(String screenshotname) {
		page.click("text=Share");
		page.click("text=Statistics");
		page.waitForTimeout(5000);
		page.locator("#board").screenshot(new Locator.ScreenshotOptions().setPath(Paths.get(screenshotname)));
	}

	@Override
	public void close() {
		playwright.close();
	}

	public void waitForClose() {
		page.waitForClose(() -> {});
	}

}

class WordTester {
	public enum CharResult {
		ABSENT,
		PRESENT,
		CORRECT
	}

	public static Predicate<String> makeFilter(String word, CharResult... results) {
		assert word.length() == results.length;

		record Matcher(char c, int min, int max, List<Integer> positions, List<Integer> notPositionts) implements Predicate<String> {

			Matcher {
				positions = List.copyOf(positions);
				notPositionts = List.copyOf(notPositionts);
			}

			@Override
			public boolean test(String t) {
				long count = t.codePoints().filter(cp -> cp == c).count();
				return count >= min && count <= max &&
						positions.stream().allMatch(i -> t.charAt(i) == c) &&
						notPositionts.stream().allMatch(i -> t.charAt(i) != c);
			}

			static Matcher of(char c) {
				return new Matcher(c, 0, Integer.MAX_VALUE, List.of(), List.of());
			}

			Matcher absent() {
				return new Matcher(c, min, min, positions, notPositionts);
			}

			Matcher containsNotAt(int pos) {
				int newMax = max == Integer.MAX_VALUE ? Integer.MAX_VALUE : max + 1;
				ArrayList<Integer> newNotPos = new ArrayList<>(notPositionts);
				newNotPos.add(pos);
				return new Matcher(c, min + 1, newMax, positions, newNotPos);
			}

			Matcher correct(int pos) {
				int newMax = max == Integer.MAX_VALUE ? Integer.MAX_VALUE : max + 1;
				ArrayList<Integer> newPos = new ArrayList<>(positions);
				newPos.add(pos);
				return new Matcher(c, min + 1, newMax, newPos, notPositionts);
			}


		}

		HashMap<Character, Matcher> chars = new HashMap<>();

		for (int i = 0; i < results.length; i++) {
			char c = word.charAt(i);

			Matcher base = chars.computeIfAbsent(c, Matcher::of);

			Matcher newMatcher = switch (results[i]) {
				case ABSENT -> base.absent();
				case PRESENT -> base.containsNotAt(i);
				case CORRECT -> base.correct(i);
			};
			chars.put(c, newMatcher);
		}
		return chars.values().stream().<Predicate<String>>map(Function.identity())
				.reduce(Predicate::and).orElseThrow();
	}
}