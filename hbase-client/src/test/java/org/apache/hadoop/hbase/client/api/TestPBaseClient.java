package org.apache.hadoop.hbase.client.api;


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by wangxiaoyi on 15/7/1.
 */
public class TestPBaseClient {

    public static final Configuration conf = HBaseConfiguration.create();
    public static final PBaseClient client = new PBaseClient(conf);
    public static final TableName tableName = TableName.valueOf("people2");
/*
    public static void main(String []args){
        //testPut();
        testScan();
    }*/


    @Test
    public void testPut(){

        List<Put> puts = new LinkedList<>();
        for(int i = 1; i < 50; i++) {
            Put put = new Put(String.format("%07d", i).getBytes());
            put.addColumn("cf".getBytes(), "name".getBytes(), ("wangxiaoyi" + i).getBytes());
            put.addColumn("cf".getBytes(), "age".getBytes(), ("" + i).getBytes());
            put.addColumn("cf".getBytes(), "job".getBytes(), ("student" + i).getBytes());
            puts.add(put);
        }
        client.batchPut(puts, tableName);
        System.out.println("done");
    }

    @Test
    public void testScan(){
        Matcher matcher = new Matcher(tableName.getNameAsString(), null)
                .setCachingRows(100)
                .setStartRow(String.format("%07d", 999998).getBytes());
               //.setStopRow(String.format("%07d", 11).getBytes());

        try (Connection connection = ConnectionFactory.createConnection(conf)) {
            try (Table table = connection.getTable(matcher.getTableName())) {
                ResultScanner rs = table.getScanner(matcher.getScan());

                Iterator<Result> it = rs.iterator();
                while (it.hasNext()){
                    Result result = it.next();
                    while (result.advance()){
                        Cell cell = result.current();
                        System.out.print(Bytes.toString(cell.getRow()) + "\t");
                        System.out.print(Bytes.toString(cell.getQualifier()) + "\t");
                        System.out.print(Bytes.toString(cell.getValue()) + "\t");
                    }
                    System.out.println();
                }

            }
        } catch (IOException e) {
            //LOG.error(e.getMessage());
        }

    }

    @Test
    public void testScanWithScanSchema(){
        Matcher matcher = new Matcher(tableName.getNameAsString(), null)
                .setCachingRows(100)
                .setStartRow(String.format("%07d", 1).getBytes());

        TableSchema schema = new TableSchema(tableName.getNameAsString());
        //schema.addColumnDescriptor("name", FIELD_RULE.repeated, FIELD_TYPE.binary);
        schema.addColumnDescriptor("age", FIELD_RULE.repeated, FIELD_TYPE.binary);

        matcher.setScanTableSchema(schema);
        System.out.println(new String(matcher.getScan().getAttribute(HConstants.SCAN_TABLE_SCHEMA)));


        try (Connection connection = ConnectionFactory.createConnection(conf)) {
            try (Table table = connection.getTable(matcher.getTableName())) {
                ResultScanner rs = table.getScanner(matcher.getScan());

                Iterator<Result> it = rs.iterator();
                while (it.hasNext()){
                    Result result = it.next();
                    while (result.advance()){
                        Cell cell = result.current();
                        System.out.print(Bytes.toString(cell.getRow()) + "\t");
                        System.out.print(Bytes.toString(cell.getQualifier()) + "\t");
                        System.out.print(Bytes.toString(cell.getValue()) + "\t");
                    }
                    System.out.println();
                }

            }
        } catch (IOException e) {
            //LOG.error(e.getMessage());
        }
    }
}
