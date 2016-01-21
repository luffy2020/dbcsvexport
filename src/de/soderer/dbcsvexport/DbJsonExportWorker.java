package de.soderer.dbcsvexport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.DbUtilities;
import de.soderer.utilities.DbUtilities.DbVendor;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.WorkerDual;
import de.soderer.utilities.WorkerParentDual;
import de.soderer.utilities.ZipUtilities;
import de.soderer.utilities.json.JsonWriter;

public class DbJsonExportWorker extends WorkerDual<Boolean> {
	// Mandatory parameters
	private DbUtilities.DbVendor dbVendor = null;
	private String hostname;
	private String dbName;
	private String username;
	private String password;
	private String sqlStatementOrTablelist;
	private String outputpath;
	
	// Default optional parameters
	private boolean log = false;
	private boolean zip = false;
	private boolean beautify = true;
	private String encoding = "UTF-8";
	private boolean createBlobFiles = false;
	private boolean createClobFiles = false;
	private Locale dateLocale = Locale.getDefault();
	private DateFormat dateFormat = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.MEDIUM, SimpleDateFormat.MEDIUM, dateLocale);
	
	private int overallExportedLines = 0;
	private long overallExportedDataAmount = 0;

	public DbJsonExportWorker(WorkerParentDual parent, DbVendor dbVendor, String hostname, String dbName, String username, String password, String sqlStatementOrTablelist, String outputpath) {
		super(parent);
		this.dbVendor = dbVendor;
		this.hostname = hostname;
		this.dbName = dbName;
		this.username = username;
		this.password = password;
		this.sqlStatementOrTablelist = sqlStatementOrTablelist;
		this.outputpath = outputpath;
	}

	public void setLog(boolean log) {
		this.log = log;
	}

	public void setZip(boolean zip) {
		this.zip = zip;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	public void setCreateBlobFiles(boolean createBlobFiles) {
		this.createBlobFiles = createBlobFiles;
	}

	public void setCreateClobFiles(boolean createClobFiles) {
		this.createClobFiles = createClobFiles;
	}

	public void setDateLocale(Locale dateLocale) {
		this.dateLocale = dateLocale;

		dateFormat = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.MEDIUM, SimpleDateFormat.MEDIUM, dateLocale);
	}

	public void setBeautify(boolean beautify) {
		this.beautify = beautify;
	}

	@Override
	public void run() {
		startTime = new Date();

		Connection connection = null;
		try {
			overallExportedLines = 0;
			connection = DbUtilities.createConnection(dbVendor, hostname, dbName, username, password.toCharArray());

			if (sqlStatementOrTablelist.toLowerCase().startsWith("select ")) {
				itemsToDo = 0;
				itemsDone = 0;

				if (!"console".equalsIgnoreCase(outputpath)) {
					if (!new File(outputpath).exists()) {
						int lastSeparator = Math.max(outputpath.lastIndexOf("/"), outputpath.lastIndexOf("\\"));
						if (lastSeparator >= 0) {
							String filename = outputpath.substring(lastSeparator + 1);
							filename = DateUtilities.replaceDatePatternInString(filename, new Date());
							outputpath = outputpath.substring(0, lastSeparator + 1) + filename;
						}
					}

					if (new File(outputpath).exists() && new File(outputpath).isDirectory()) {
						outputpath = outputpath + File.separator + "export_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
					}
				}

				export(connection, sqlStatementOrTablelist, outputpath);

				result = !cancel;
			} else {
				showItemStart("Scanning tables ...");
				showUnlimitedProgress();
				List<String> tablesToExport = DbUtilities.getAvailableTables(connection, sqlStatementOrTablelist);
				itemsToDo = tablesToExport.size();
				itemsDone = 0;
				boolean success = true;
				for (int i = 0; i < tablesToExport.size() && success && !cancel; i++) {
					showProgress(true);
					String tableName = tablesToExport.get(i);
					subItemsToDo = 0;
					subItemsDone = 0;
					String keyColumn = DbUtilities.getPrimaryKeyColumn(connection, tableName);
					showItemStart(tableName);

					try {
						String nextOutputFilePath = outputpath;
						if (!"console".equalsIgnoreCase(outputpath)) {
							nextOutputFilePath = outputpath + File.separator + tableName.toLowerCase();
						}
						export(connection, "SELECT * FROM " + tableName + (Utilities.isNotEmpty(keyColumn) ? " ORDER BY " + keyColumn : ""), nextOutputFilePath);
					} catch (Exception e) {
						error = e;
						success = false;
					}

					showItemDone();

					itemsDone++;
				}
				result = success && !cancel;
			}
		} catch (Exception e) {
			error = e;
			result = false;
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
		showDone();
	}

	public void export(Connection connection, String sqlStatement, String outputFilePath) throws Exception {
		OutputStream outputStream = null;
		OutputStream logOutputStream = null;
		Statement statement = null;
		ResultSet resultSet = null;
		JsonWriter jsonWriter = null;

		try {
			if (!"console".equalsIgnoreCase(outputFilePath)) {
				if (zip) {
					if (!outputFilePath.toLowerCase().endsWith(".zip")) {
						outputFilePath = outputFilePath + ".zip";
					}
				} else if (!outputFilePath.toLowerCase().endsWith(".json")) {
					outputFilePath = outputFilePath + ".json";
				}

				if (new File(outputFilePath).exists()) {
					throw new DbCsvExportException("Outputfile already exists: " + outputFilePath);
				}

				if (log) {
					logOutputStream = new FileOutputStream(new File(outputFilePath + "." + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".log"));

					logToFile(logOutputStream, "File: " + new File(outputFilePath).getName());
					logToFile(logOutputStream, "Format: JSON");
					logToFile(logOutputStream, "Zip: " + zip);
					logToFile(logOutputStream, "Encoding: " + encoding);
					logToFile(logOutputStream, "SqlStatement: " + sqlStatement);
					logToFile(logOutputStream, "OutputFormatLocale: " + dateLocale.getLanguage());
					logToFile(logOutputStream, "OutputFormatLocale: " + dateLocale.getLanguage());
					logToFile(logOutputStream, "CreateBlobFiles: " + createBlobFiles);
					logToFile(logOutputStream, "CreateClobFiles: " + createClobFiles);
				}

				if (currentItemName == null) {
					logToFile(logOutputStream, "Start: " + DateFormat.getDateTimeInstance().format(startTime));
				} else {
					logToFile(logOutputStream, "Start: " + DateFormat.getDateTimeInstance().format(startTimeSub));
				}

				if (zip) {
					outputStream = ZipUtilities.openNewZipOutputStream(new FileOutputStream(new File(outputFilePath)));
					String entryFileName = outputFilePath.substring(0, outputFilePath.length() - 4);
					entryFileName = entryFileName.substring(entryFileName.lastIndexOf(File.separatorChar) + 1);
					if (!entryFileName.toLowerCase().endsWith(".json")) {
						entryFileName += ".json";
					}
					ZipEntry entry = new ZipEntry(entryFileName);
					entry.setTime(new Date().getTime());
					((ZipOutputStream) outputStream).putNextEntry(entry);
				} else {
					outputStream = new FileOutputStream(new File(outputFilePath));
				}
			} else {
				outputStream = new ByteArrayOutputStream();
			}

			statement = connection.createStatement();

			if (currentItemName == null) {
				showUnlimitedProgress();
			} else {
				showUnlimitedSubProgress();
			}

			if (dbVendor == DbVendor.Oracle) {
				resultSet = statement.executeQuery("SELECT COUNT(*) FROM(" + sqlStatement + ")");
			} else if (dbVendor == DbVendor.MySQL) {
				resultSet = statement.executeQuery("SELECT COUNT(*) FROM(" + sqlStatement + ") AS data");
			} else if (dbVendor == DbVendor.PostgreSQL) {
				resultSet = statement.executeQuery("SELECT COUNT(*) FROM(" + sqlStatement + ") AS data");
			} else {
				throw new Exception("Unknown db vendor");
			}
			resultSet.next();
			int linesToExport = resultSet.getInt(1);
			logToFile(logOutputStream, "Lines to export: " + linesToExport);

			if (currentItemName == null) {
				itemsToDo = linesToExport;
				showProgress();
			} else {
				subItemsToDo = linesToExport;
				showItemProgress();
			}

			resultSet.close();
			resultSet = null;

			jsonWriter = new JsonWriter(outputStream, encoding);
			jsonWriter.setUglify(!beautify);

			resultSet = statement.executeQuery(sqlStatement);
			ResultSetMetaData metaData = resultSet.getMetaData();

			if (currentItemName == null) {
				itemsDone = 0;
				showProgress();
			} else {
				subItemsDone = 0;
				showItemProgress();
			}

			// Write headers
			List<String> headers = new ArrayList<String>();
			for (int i = 1; i <= metaData.getColumnCount(); i++) {
				headers.add(metaData.getColumnName(i));
			}

			if (currentItemName == null) {
				itemsDone++;
				showProgress();
			} else {
				subItemsDone++;
				showItemProgress();
			}

			// Write values
			jsonWriter.openJsonArray();
			while (resultSet.next() && !cancel) {
				jsonWriter.openJsonObject();
				for (int i = 1; i <= metaData.getColumnCount(); i++) {
					jsonWriter.openJsonObjectProperty(headers.get(i - 1));
					if (resultSet.getObject(i) == null) {
						jsonWriter.addSimpleJsonObjectPropertyValue(null);
					} else if (metaData.getColumnType(i) == Types.BLOB) {
						if (createBlobFiles) {
							File blobOutputFile = File.createTempFile(outputFilePath.substring(0, outputFilePath.length() - 4) + "_", ".blob" + (zip ? ".zip" : ""),
									new File(outputFilePath).getParentFile());
							try (InputStream input = resultSet.getBlob(i).getBinaryStream()) {
								OutputStream output = null;
								try {
									if (zip) {
										output = ZipUtilities.openNewZipOutputStream(new FileOutputStream(blobOutputFile));
										String entryFileName = blobOutputFile.getName().substring(0, blobOutputFile.getName().lastIndexOf("."));
										ZipEntry entry = new ZipEntry(entryFileName);
										entry.setTime(new Date().getTime());
										((ZipOutputStream) output).putNextEntry(entry);
									} else {
										output = new FileOutputStream(blobOutputFile);
									}
									Utilities.copy(input, output);
								} finally {
									Utilities.closeQuietly(output);
								}
								overallExportedDataAmount += blobOutputFile.length();
								jsonWriter.addSimpleJsonObjectPropertyValue(blobOutputFile.getName());
							} catch (Exception e) {
								logToFile(logOutputStream, "Cannot create blob file '" + blobOutputFile.getAbsolutePath() + "': " + e.getMessage());
								jsonWriter.addSimpleJsonObjectPropertyValue("Error creating blob file '" + blobOutputFile.getAbsolutePath() + "'");
							}
						} else {
							byte[] data = Utilities.toByteArray(resultSet.getBlob(i).getBinaryStream());
							jsonWriter.addSimpleJsonObjectPropertyValue(Base64.getEncoder().encodeToString(data));
						}
					} else if (metaData.getColumnType(i) == Types.CLOB) {
						if (createClobFiles) {
							File clobOutputFile = File.createTempFile(outputFilePath.substring(0, outputFilePath.length() - 4) + "_", ".clob" + (zip ? ".zip" : ""),
									new File(outputFilePath).getParentFile());
							try (Reader input = resultSet.getClob(i).getCharacterStream()) {
								OutputStream output = null;
								try {
									if (zip) {
										output = ZipUtilities.openNewZipOutputStream(new FileOutputStream(clobOutputFile));
										String entryFileName = clobOutputFile.getName().substring(0, clobOutputFile.getName().lastIndexOf("."));
										ZipEntry entry = new ZipEntry(entryFileName);
										entry.setTime(new Date().getTime());
										((ZipOutputStream) output).putNextEntry(entry);
									} else {
										output = new FileOutputStream(clobOutputFile);
									}
									Utilities.copy(input, output, "UTF-8");
								} finally {
									Utilities.closeQuietly(output);
								}
								overallExportedDataAmount += clobOutputFile.length();
								jsonWriter.addSimpleJsonObjectPropertyValue(clobOutputFile.getName());
							} catch (Exception e) {
								logToFile(logOutputStream, "Cannot create blob file '" + clobOutputFile.getAbsolutePath() + "': " + e.getMessage());
								jsonWriter.addSimpleJsonObjectPropertyValue("Error creating blob file '" + clobOutputFile.getAbsolutePath() + "'");
							}
						} else {
							jsonWriter.addSimpleJsonObjectPropertyValue(resultSet.getString(i));
						}
					} else if (metaData.getColumnType(i) == Types.DATE || metaData.getColumnType(i) == Types.TIMESTAMP) {
						jsonWriter.addSimpleJsonObjectPropertyValue(dateFormat.format(resultSet.getObject(i)));
					} else if (metaData.getColumnType(i) == Types.DECIMAL || metaData.getColumnType(i) == Types.DOUBLE || metaData.getColumnType(i) == Types.FLOAT) {
						jsonWriter.addSimpleJsonObjectPropertyValue(resultSet.getObject(i));
					} else if (metaData.getColumnType(i) == Types.BIGINT || metaData.getColumnType(i) == Types.BIT || metaData.getColumnType(i) == Types.INTEGER
							|| metaData.getColumnType(i) == Types.NUMERIC || metaData.getColumnType(i) == Types.SMALLINT || metaData.getColumnType(i) == Types.TINYINT) {
						jsonWriter.addSimpleJsonObjectPropertyValue(resultSet.getObject(i));
					} else {
						jsonWriter.addSimpleJsonObjectPropertyValue(resultSet.getString(i));
					}
				}
				jsonWriter.closeJsonObject();

				if (currentItemName == null) {
					itemsDone++;
					showProgress();
				} else {
					subItemsDone++;
					showItemProgress();
				}
			}
			jsonWriter.closeJsonArray();
			
			long exportedLines;
			if (currentItemName == null) {
				exportedLines = itemsDone;
			} else {
				exportedLines = subItemsDone;
			}

			if (currentItemName == null) {
				endTime = new Date();
			} else {
				endTimeSub = new Date();
			}

			if ("console".equalsIgnoreCase(outputFilePath)) {
				jsonWriter.flush();
				System.out.println(new String(((ByteArrayOutputStream) outputStream).toByteArray(), "UTF-8"));
			}

			if (exportedLines > 0) {
				logToFile(logOutputStream, "Exported lines: " + exportedLines);

				int elapsedTimeInSeconds;
				if (currentItemName == null) {
					elapsedTimeInSeconds = (int) (endTime.getTime() - startTime.getTime()) / 1000;
				} else {
					elapsedTimeInSeconds = (int) (endTimeSub.getTime() - startTimeSub.getTime()) / 1000;
				}
				if (elapsedTimeInSeconds > 0) {
					int linesPerSecond = (int) (exportedLines / elapsedTimeInSeconds);
					logToFile(logOutputStream, "Export speed: " + linesPerSecond + " lines/second");
				} else {
					logToFile(logOutputStream, "Export speed: immediately");
				}
				
				if (new File(outputFilePath).exists()) {
					logToFile(logOutputStream, "Exported data amount: " + Utilities.getHumanReadableNumber(new File(outputFilePath).length(), "B"));
				}
			}

			if (currentItemName == null) {
				logToFile(logOutputStream, "End: " + DateFormat.getDateTimeInstance().format(endTime));
				logToFile(logOutputStream, "Time elapsed: " + DateUtilities.getHumanReadableTimespan(endTime.getTime() - startTime.getTime(), true));
			} else {
				logToFile(logOutputStream, "End: " + DateFormat.getDateTimeInstance().format(endTimeSub));
				logToFile(logOutputStream, "Time elapsed: " + DateUtilities.getHumanReadableTimespan(endTimeSub.getTime() - startTimeSub.getTime(), true));
			}

			overallExportedLines += exportedLines;
		} catch (Exception e) {
			try {
				logToFile(logOutputStream, "Error: " + e.getMessage());
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			throw e;
		} finally {
			if (resultSet != null) {
				try {
					resultSet.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (statement != null) {
				try {
					statement.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (jsonWriter != null) {
				try {
					jsonWriter.flush();
				} catch (Exception e) {
					e.printStackTrace();
				}

				if (zip) {
					if (outputStream != null) {
						try {
							((ZipOutputStream) outputStream).closeEntry();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}

				try {
					jsonWriter.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (logOutputStream != null) {
				try {
					logOutputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		if (new File(outputFilePath).exists()) {
			overallExportedDataAmount += new File(outputFilePath).length();
		}
	}

	private static void logToFile(OutputStream logOutputStream, String message) throws Exception {
		if (logOutputStream != null) {
			logOutputStream.write((message + "\n").getBytes("UTF-8"));
		}
	}

	public int getOverallExportedLines() {
		return overallExportedLines;
	}
	
	public long getOverallExportedDataAmount() {
		return overallExportedDataAmount;
	}
}