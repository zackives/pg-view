package edu.upenn.cis.db.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class Util {
	static long lastTime;
	
	private static HashMap<String, Integer> counter = new HashMap<String, Integer>();
	private static HashMap<Integer, Long> timer = new HashMap<Integer, Long>(); 
	private static HashMap<String, HashMap<String, Integer>> varDicEncodings = new HashMap<String, HashMap<String, Integer>>();;
	private static HashMap<String, Integer> varDicEncodingIndexes = new HashMap<String, Integer>();

	private static int timerId = 0;
	
	private static boolean flag_console = true;
	
	// https://stackoverflow.com/questions/5762491/how-to-print-color-in-console-using-system-out-println
	public static final String ANSI_RESET = "\u001B[0m";
	public static final String ANSI_BLACK = "\u001B[30m";
	public static final String ANSI_RED = "\u001B[31m";
	public static final String ANSI_GREEN = "\u001B[32m";
	public static final String ANSI_YELLOW = "\u001B[33m";
	public static final String ANSI_BLUE = "\u001B[34m";
	public static final String ANSI_PURPLE = "\u001B[35m";
	public static final String ANSI_CYAN = "\u001B[36m";
	public static final String ANSI_WHITE = "\u001B[37m";
	
	// Background
    public static final String BLACK_BACKGROUND = "\033[40m";  // BLACK
    public static final String RED_BACKGROUND = "\033[41m";    // RED
    public static final String GREEN_BACKGROUND = "\033[42m";  // GREEN
    public static final String YELLOW_BACKGROUND = "\033[43m"; // YELLOW
    public static final String BLUE_BACKGROUND = "\033[44m";   // BLUE
    public static final String PURPLE_BACKGROUND = "\033[45m"; // PURPLE
    public static final String CYAN_BACKGROUND = "\033[46m";   // CYAN
    public static final String WHITE_BACKGROUND = "\033[47m";  // WHITE
	
    public static String getItemsWithComma(Collection<String> set) {
    	StringBuilder str = new StringBuilder();
    	int index = 0;
    	for (String s : set) {
    		if (index > 0) {
    			str.append(", ");
    		}
    		str.append(s);
    		index++;
    	}
    	return str.toString();
    }
    
	public static void resetVarDicEncoding(String name) {
		if (varDicEncodings.containsKey(name) == true) {
			varDicEncodings.remove(name);
		}
	}
	
	public static int getVarDicEncoding(String name, String value) {
		if (varDicEncodings.containsKey(name) == false) {
			varDicEncodings.put(name, new HashMap<String, Integer>());
			varDicEncodingIndexes.put(name, 0);
		}
		HashMap<String, Integer> m = varDicEncodings.get(name);
		if (m.containsKey(value) == false) {
			int index = varDicEncodingIndexes.get(name);
			m.put(value, index);
			varDicEncodingIndexes.put(name, index+1);
		}
		
//		System.out.println("varDicEncodings: " + varDicEncodings);
//		System.out.println("varDicEncodingIndexes: " + varDicEncodingIndexes);
//		System.out.println("getVarDicEncoding name: " + name + " value: " + value + " index: " + m.get(value));
		return m.get(value);
	}

	/**
	 * Inner class for console logging.
	 * http://mihai-nita.net/2013/06/03/eclipse-plugin-ansi-in-console/
	 * @author sbnet21
	 *
	 */
	public static class Console {
		private static boolean enable = true;
		
		/**
		 * Set the enable flag.
		 * @param e
		 */
		public static void setEnable(boolean e) {
			enable = e;
		}
		
		/**
		 * Print log on console
		 * @param msg
		 */
		public static void log(String msg) {
			if (enable == true) {
				System.out.print(ANSI_GREEN + msg + ANSI_RESET);
			}
		}
		
		/**
		 * Print log with line break on console
		 * @param msg
		 */
		public static void logln(String msg) {
			log(msg);
			log("\n");
		}
		
		/**
		 * Print error on console
		 * @param msg
		 */
		public static void err(String msg) {
			if (enable == true) {
				System.out.print(ANSI_RED + msg + ANSI_RESET);
			}
		}

		/**
		 * Print error with line break on console
		 * @param msg
		 */
		public static void errln(String msg) {
			err("[ERROR] ");
			err(msg);
			err("\n");
		}

	}
	
	public static void writeToFile(String filename, String content) {
		writeToFile(filename, content, false);
	}
	
	public static void writeToFile(String filename, String content, boolean isAppend) {
	    try {
	        FileWriter myWriter = new FileWriter(filename, isAppend);
	        myWriter.write(content);
	        myWriter.close();
	      } catch (IOException e) {
	        System.out.println("An error occurred to write filename: " + filename);
	        e.printStackTrace();
	      }		
	}
	
	public static void setConsole(boolean flag) {
		flag_console = flag;
	}

	public static void console_log(String msg) {
		console_log(msg, 0);
	}
	
	public static void console_logln(String msg, int color) {
		console_log(msg+"\n", color);
	}
		
	public static void console_log(String msg, int color) {
		String msg_str = msg;
		if (flag_console == true) {
			if (color > 0) {
				if (color == 1) {
					msg_str = ANSI_RED + msg;
				} else if (color == 2) {
					msg_str = ANSI_GREEN + msg;
				} else if (color == 3) {
					msg_str = ANSI_CYAN + msg;
				} else if (color == 4) {
					msg_str = ANSI_PURPLE + msg;
				} else {
					msg_str = ANSI_BLUE + msg;	
				}
				msg_str += ANSI_RESET;
			}
			System.out.print(msg_str);			
		}
	}
	
	/**
	 * Incremental counter 
	 * @param name counter name 
	 * @return
	 */
	public static int getCounter(String name) {
		int c = 0;
		if (counter.containsKey(name) == true) {
			c = counter.get(name);
			counter.replace(name, c+1);
			
		} else {
			counter.put(name,  1);
		}
		return c;
	}
	
	public static void resetCounter(String name) {
		if (counter.containsKey(name) == true) {
			counter.remove(name);
		}
	}
	
	public static int startTimer() {
		long lastTime = System.nanoTime();
		int id = timerId++;
		timer.put(id, lastTime);
		
		return id;
	}
	
	public static long getElapsedTimeMicro(int id) {
		long curTime = System.nanoTime();
		long elapsedTime = curTime - timer.get(id);
		timer.put(id, curTime);
		
		return elapsedTime / 1000;
	}
	
	public static long getElapsedTime(int id) {
		long curTime = System.nanoTime();
		long elapsedTime = curTime - timer.get(id);
		timer.put(id, curTime);
		
		return elapsedTime / 1000000;
	}

	public static void resetTimer() {
		lastTime = System.nanoTime();
	}

	public static long getLapTime() {
		long newTime = System.nanoTime();
		long elapsedTime = newTime - lastTime;
		lastTime = newTime;

		return elapsedTime / 1000000;
	}	

	/**
	 * Return a string that is backslashed to double quotes
	 * @param str
	 * @return
	 */
	public static String addSlashes(String str) {
		return str.replaceAll("\"", "\\\\\"");		
	}

	/**
	 * Return a string where backslashes are removed.
	 * @param str
	 * @return
	 */
	public static String removeSlashes(String str) {
		return str.replaceAll("\\\\\"", "\"");		
	}
	
	
	/**
	 * Return a string after removing surrounding double quotes
	 * @param str
	 * @return
	 */
	public static String removeQuotes(String str) {
		String retStr = "";
		if (str.length() > 2) {
			String first = str.substring(0, 1);
			String last = str.substring(str.length()-1);
			if ((first.contentEquals("\"") && last.contentEquals("\"")) || (first.contentEquals("'") && last.contentEquals("'"))) {
				retStr = str.substring(1, str.length()-1);
			} else {
				retStr = str;
			}
		} else {
			retStr = str;
		}
		return retStr;
	}
	
	/**
	 * Return a string enclosed by double quotes.
	 * @param str
	 * @return
	 */
	public static String addQuotes(String str) {
		return "\"" + str + "\"";
	}

	public static ArrayList<String> getExternalCommand(String cmd) {
		ArrayList<String> result = new ArrayList<String>();
		try {
			Runtime rt = Runtime.getRuntime();
			Process pr = rt.exec(cmd);

			BufferedReader input = new BufferedReader(new InputStreamReader(pr.getInputStream()));

			String line = null;
			while ((line = input.readLine()) != null) {
				result.add(line);
			}

			int exitVal = pr.waitFor();
		} catch (Exception e) {
			System.out.println(e.toString());
			e.printStackTrace();
		}
		return result;		
	}
	
	/**
	 * Get a string from a filepath 
	 * @param filepath
	 * @return
	 */
	public static String getStringFromFilePath(String filepath) {
		BufferedReader reader = null;
		String str = "";
	
		try {
		    File file = new File(filepath);
		    reader = new BufferedReader(new FileReader(file));
	
		    String line;
		    while ((line = reader.readLine()) != null) {
		        str = str + line + "\n";
		    }
		} catch (IOException e) {
		    e.printStackTrace();
		} finally {
		    try {
		        reader.close();
		    } catch (IOException e) {
		        e.printStackTrace();
		    }
		}
		
		return str;
	}	
	
	public static String getDateTime() {
		String pattern = "yyyy-MM-dd HH:mm:ss.SSS";
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
		String date = simpleDateFormat.format(new Date());
		
		return date;		
	}
	
	public static String arrayToString(List<String> arr) {
		StringBuilder str = new StringBuilder();
		for (int i = 0; i < arr.size(); i++) {
			str.append("\n").append(arr.get(i));
		}
		return str.toString();
	}
}
