package com.nhom25;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.nhom25.Util.getCurrentDate;
import static com.nhom25.Util.parseArgs;
import static org.apache.spark.sql.functions.*;
import static org.apache.spark.sql.functions.lit;

public class Transformation {
    static String executionDate, year, month, day;

    public static void main(String[] args) throws IOException {
        Map<String, String> argMap = parseArgs(args);
        executionDate = argMap.get("-executionDate");
        if (executionDate == null) {
            executionDate = getCurrentDate();
        }
        var runTime = executionDate.split("-");
        year = runTime[0];
        month = runTime[1];
        day = runTime[2];
        // Initialize Spark session
        SparkSession spark = SparkSession
                .builder()
                .appName("Statement Report")
                .config("spark.sql.catalogImplementation", "hive")
                .config("hive.metastore.uris", "thrift://localhost:9083")
                .config("hive.exec.dynamic.partition", "true")
                .config("hive.exec.dynamic.partition.mode", "nonstrict")
                .master("local[*]")
                .enableHiveSupport()
                .getOrCreate();

        // Load data to Spark DF
        Dataset<Row> datalakeDF = spark.read().parquet("hdfs://192.168.1.188:9000/datalake/").drop("year", "month", "day");

        // Run the reports asynchronously
        CompletableFuture<Void> totalAmountByMonthFuture = CompletableFuture.runAsync(() -> saveTotalAmountByMonth(spark, datalakeDF));
        CompletableFuture<Void> totalAmountByBankFuture = CompletableFuture.runAsync(() -> saveTotalAmountByBank(spark, datalakeDF));
        CompletableFuture<Void> transactionCountByCodeFuture = CompletableFuture.runAsync(() -> saveTransactionCountByCode(spark, datalakeDF));
        CompletableFuture<Void> userTransactionReportFuture = CompletableFuture.runAsync(() -> saveUserTransactionReport(spark, datalakeDF));

        // Wait for all tasks to complete
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                totalAmountByMonthFuture,
                totalAmountByBankFuture,
                transactionCountByCodeFuture,
                userTransactionReportFuture
        );

        // Block the main thread until all asynchronous tasks complete
        allOf.join();

        // Stop Spark session after all tasks are done
        spark.stop();
    }

    // Function to save total amount by month
    private static void saveTotalAmountByMonth(SparkSession spark, Dataset<Row> df) {
        Dataset<Row> updatedDF = df
                .withColumn("transaction_date", to_date(col("transaction_date"), "dd/MM/yyyy"))
                .withColumn("transaction_day", date_format(col("transaction_date"), "d").cast("long"));
        Dataset<Row> totalByMonthDF = updatedDF.groupBy( "transaction_day")
                .agg(sum("credit").alias("total_amount"));
        Dataset<Row> result = addPartitionColumns(totalByMonthDF);

        result.write()
                .format("hive")
                .partitionBy("year", "month","day")
                .mode(SaveMode.Append)
                .saveAsTable("total_amount_by_day");
    }

    // Function to save total amount by bank
    private static void saveTotalAmountByBank(SparkSession spark, Dataset<Row> df) {
        Dataset<Row> totalByBankDF = df.groupBy("bank")
                .agg(sum("credit").as("total_amount"));
        Dataset<Row> resultDF = addPartitionColumns(totalByBankDF);

        resultDF.write()
                .format("hive")
                .partitionBy("year", "month", "day")
                .mode(SaveMode.Append)
                .saveAsTable("total_amount_by_bank");
    }

    // Function to save transaction count by code
    private static void saveTransactionCountByCode(SparkSession spark, Dataset<Row> df) {
        Dataset<Row> countByTransactionCodeDF = df.groupBy("no_or_code")
                .agg(count("no_or_code").alias("transaction_count"));
        Dataset<Row> resultDF = addPartitionColumns(countByTransactionCodeDF);

        resultDF.write()
                .format("hive")
                .partitionBy("year", "month", "day")
                .mode(SaveMode.Append)
                .saveAsTable("transaction_count_by_code");
    }

    // Function to save user transaction report
    private static void saveUserTransactionReport(SparkSession spark, Dataset<Row> df) {
        // Replace null or empty values in the name column with "Vietcombank"
        Dataset<Row> updatedDF = df.withColumn("name",
                when(col("name").isNull().or(trim(col("name")).equalTo("")), "Vietcombank")
                        .otherwise(col("name")));

        // Group by name and aggregate the required metrics
        Dataset<Row> userTransactionReportDF = updatedDF.groupBy("name")
                .agg(count("no_or_code").alias("transaction_count"),
                        avg("credit").alias("average_credit"),
                        sum("credit").alias("total_credit"));

        // Order by total_credit descending and limit to top 10 users
        Dataset<Row> topUsersDF = userTransactionReportDF
                .orderBy(col("total_credit").desc())
                .limit(10);

        // Add partition columns
        Dataset<Row> resultDF = addPartitionColumns(topUsersDF);

        // Write the result to Hive table
        resultDF.write()
                .format("hive")
                .partitionBy("year", "month", "day")
                .mode(SaveMode.Append)
                .saveAsTable("user_transaction_report");
    }

    private static Dataset<Row> addPartitionColumns(Dataset<Row> df) {
        return df.withColumn("year", lit(year))
                .withColumn("month", lit(month))
                .withColumn("day", lit(day))
                .withColumn("date_report", concat(lit(year), lit("-"), lit(month), lit("-"), lit(day)));
    }

}