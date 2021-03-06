package org.apache.hadoop.hbase.regionserver.memstore;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parquet.column.ColumnDescriptor;
import parquet.schema.MessageType;
import parquet.schema.MessageTypeParser;
import parquet.schema.Type;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * Created by wangxiaoyi on 15/5/6.
 *
 * implement memstore for mutation
 *
 */
public class PMemStoreImpl implements PMemStore{

    private static final Log LOG = LogFactory.getLog(PMemStore.class);

    public final static long FIXED_OVERHEAD = ClassSize.align(
            ClassSize.OBJECT + (4 * ClassSize.REFERENCE) + (2 * Bytes.SIZEOF_LONG));

    public final static long DEEP_OVERHEAD = ClassSize.align(FIXED_OVERHEAD +
            ClassSize.ATOMIC_LONG + (0 * ClassSize.TIMERANGE_TRACKER) +
            (0 * ClassSize.CELL_SKIPLIST_SET) + (2 * ClassSize.CONCURRENT_SKIPLISTMAP));



    private Configuration conf;

    private volatile Map<byte[], Mutation> rowInMem;
    private volatile Map<byte[], Mutation> snapshotRowInMem;

    private volatile byte[] startkey = null;
    private volatile byte[] endkey = null;

    // Used to track own heapSize
    private AtomicLong memstoreSize;
    private volatile long snapshotSize;

    // Used to track when to flush
    volatile long timeOfOldestEdit = Long.MAX_VALUE;

    volatile long snapshotId;

    public PMemStoreImpl(Configuration conf){
        this.conf = conf;
        rowInMem = new ConcurrentSkipListMap<>(Bytes.BYTES_COMPARATOR);
        snapshotRowInMem = new ConcurrentSkipListMap<>(Bytes.BYTES_COMPARATOR);
        memstoreSize = new AtomicLong(DEEP_OVERHEAD);
        snapshotSize = 0;
    }

    /**
     * insert mutation into memstore
     * @param m
     * @return
     */

    @Override
    public long add(Mutation m) throws IOException{
        Mutation mutation = rowInMem.get(m.getRow());
        if(mutation != null){
            if(m instanceof Put)
                ((Put)mutation).mergePut((Put) m);
            else {
                //TODO: other mutation different merge function
            }
        }else {

            //TODO : make a efficient implementation
            if(startkey == null){
                startkey = m.getRow();
            }else {
                if(Bytes.compareTo(m.getRow(), startkey) < 0){
                    startkey = m.getRow();
                }
            }

            if(endkey == null){
                endkey = m.getRow();
            }else {
                if(Bytes.compareTo(endkey, m.getRow()) < 0){
                    endkey = m.getRow();
                }
            }

            rowInMem.put(m.getRow(), m);
        }
        memstoreSize.getAndAdd(m.heapSize());
        setOldestEditTimeToNow();
        return m.heapSize();
    }

    /**
     * get row from the memstore
     *
     * @param row
     */
    @Override
    public Mutation get(byte[] row) {
        Mutation m = rowInMem.get(row);
        return m;
    }

    /**
     * @return num in memory
     */
    public int getRecordCount(){
        return rowInMem.size();
    }

    /**
     * Write a delete
     *
     * @param m
     * @return approximate size of the passed Mutation
     */
    @Override
    public long delete(Mutation m) {
        if(m == null || rowInMem.get(m.getRow()) == null)
            return 0;
        else {
            rowInMem.remove(m.getRow());
        }
        memstoreSize.getAndSet(memstoreSize.get() - m.heapSize());
        setOldestEditTimeToNow();
        return m.heapSize();
    }

    /**
     * Creates a snapshot of the current memstore. Snapshot must be cleared by call to
     * {@link #clearSnapshot(long)}.
     *
     * @return {@link PMemStoreSnapshot}
     */
    @Override
    public PMemStoreSnapshot snapshot() {
        if (!this.snapshotRowInMem.isEmpty()) {
            //snapshotRowInMem.clear();
            LOG.warn("Snapshot called again without clearing previous. " +
                    "Doing nothing. Another ongoing flush or did we fail last attempt?");
            return null;
        }else {
            snapshotId = EnvironmentEdgeManager.currentTime();
            this.snapshotSize = dataSize();
            if(! rowInMem.isEmpty()){
                this.snapshotRowInMem = this.rowInMem;
                this.rowInMem = new ConcurrentSkipListMap<>(Bytes.BYTES_COMPARATOR);
                this.memstoreSize.set(DEEP_OVERHEAD);
                timeOfOldestEdit = Long.MAX_VALUE;

            }

            PMemStoreSnapshot snapshot = new PMemStoreSnapshot(snapshotId,
                    snapshotRowInMem.size(),
                    snapshotSize,
                    getScanner(this.snapshotRowInMem, null),
                    startkey, endkey);

            this.startkey = null;
            this.endkey = null;
            return snapshot;
        }
    }

    /**
     * Clears the current snapshot of the Memstore.
     *
     * @param id
     * @throws UnexpectedStateException
     * @see #snapshot()
     */
    @Override
    public void clearSnapshot(long id) throws UnexpectedStateException {
        //MemStoreLAB tmpAllocator = null;
        if (this.snapshotId != id) {
            throw new UnexpectedStateException("Current snapshot id is " + this.snapshotId + ",passed "
                    + id);
        }
        // OK. Passed in snapshot is same as current snapshot. If not-empty,
        // create a new snapshot and let the old one go.
        if (!this.snapshotRowInMem.isEmpty()) {
            this.snapshotRowInMem = new ConcurrentSkipListMap<>(Bytes.BYTES_COMPARATOR);
        }
        this.snapshotSize = 0l;
        this.snapshotId = -1l;
    }

    public long getCurrSnapshotId(){
        return snapshotId;
    }


    /**
     * @return Approximate 'exclusive deep size' of implementing object.  Includes
     * count of payload and hosting object sizings.
     */
    @Override
    public long heapSize() {
        return this.memstoreSize.get();
    }

    @Override
    public long getFlushableSize() {
        return this.snapshotSize > 0 ? snapshotSize : size();
    }


    /**
     * @return Oldest timestamp of all the Mutations in the MemStore
     */
    @Override
    public long timeOfOldestEdit() {
        return this.timeOfOldestEdit;
    }

    @Override
    public byte[] getStartKey() {
        return this.startkey;
    }

    @Override
    public byte[] getEndKey() {
        return this.endkey;
    }

    /**
     * @return Total memory occupied by this MemStore.
     */
    @Override
    public long size() {
        return heapSize();
    }

    public long dataSize(){
        return size() - DEEP_OVERHEAD;
    }

    void setOldestEditTimeToNow() {
        if (timeOfOldestEdit == Long.MAX_VALUE) {
            timeOfOldestEdit = EnvironmentEdgeManager.currentTime();
        }
    }

    /**
     * create scanner for {@link PMemStore}
     *
     * @return {@link PMemStoreScanner}
     */
    @Override
    public RowScanner getScanner(Scan scan) {
        return new PMemStoreScanner(this.rowInMem, scan);
    }

    public RecordScanner getSnapshotScanner(Scan scan){
        return new PMemStoreScanner(snapshotRowInMem, scan);
    }

    public RowScanner getScanner(Map<byte[], Mutation> rowInMem, Scan scan) {
        return new PMemStoreScanner(rowInMem, scan);
    }

    /**
     * return the scanner heap
     * of current kv scanner and snapshot scanner
     * @param startkey
     * @return
     */
    public ScannerHeap getRecordScanner(byte[] startkey, Scan scan){

        List<RecordScanner> scanners = new LinkedList<>();
        if( ! snapshotRowInMem.isEmpty() ) {
            PMemStoreScanner snapshotScanner = new PMemStoreScanner(snapshotRowInMem, scan);
            snapshotScanner.seek(startkey);
            scanners.add(snapshotScanner);
        }
        if( ! rowInMem.isEmpty() ){
            PMemStoreScanner memStoreScanner = new PMemStoreScanner(rowInMem, scan);
            memStoreScanner.seek(startkey);
            scanners.add(memStoreScanner);
        }
        ScannerHeap heap = null;
        try {
            heap = new ScannerHeap(scanners, new RecordScannerComparator());
        }catch (IOException ioe){
            LOG.error("create record scanner error : " + ioe);
        }finally {
            return heap;
        }
    }



    void dump() {
       for(Map.Entry<byte[], Mutation> en : rowInMem.entrySet()){
           try {
               LOG.info(((Mutation) en.getValue()).toJSON());
           }catch (IOException ioe){
               LOG.error(ioe);
           }
       }
    }




    /**
     * row scanner for {@link PMemStore}
     */
    class PMemStoreScanner implements RowScanner, InternalRecordScanner{

       // private final Logger LOG = LoggerFactory.getLogger(PMemStoreScanner.class);

        private byte[] curr = null;
        private byte[] next = null;
        private Iterator<byte []> it =null;
        private int countLeft = 0;
        private List<byte[]> filterColumns = new LinkedList<>();


        private Map<byte[], Mutation> rowInMem;

        public PMemStoreScanner(Map<byte[], Mutation> rowInMem, Scan scan){
            this.rowInMem = rowInMem;
            countLeft = rowInMem.size();
            initScanFilter(scan);
            seek();
        }

        /**
         * init the scan filter with the read schema
         * @param scan
         */
        public void initScanFilter(Scan scan){
            String schema = new String(scan.getAttribute(HConstants.SCAN_TABLE_SCHEMA));
            try {
                if (scan != null && schema != null && !schema.isEmpty()) {
                    MessageType readSchema = MessageTypeParser.parseMessageType(schema);
                    //readSchema.getFields();
                    List<Type>  types = readSchema.getFields();
                    for(Type type : types){
                        String  columnName = type.getName();
                        if(columnName.startsWith("cf"))// fetch the real column name
                            columnName = columnName.substring(3);
                        filterColumns.add(columnName.getBytes());
                    }

                }
            }catch (Exception e){
                //TODO: send the exception back to the client
                LOG.error("parse the message schema error" + e);
            }
        }

        /**
         * seek the query row
         * @param row
         */
        public void seek(byte[] row){

            if(Bytes.compareTo(row, HConstants.EMPTY_START_ROW) == 0
                    || row == null
                    || rowInMem == null
                    || rowInMem.size() == 0)
                return;

            Set<byte []> rows = rowInMem.keySet();
            it = rows.iterator();
            boolean seekEd = false;
            while (it.hasNext()){
                curr = it.next();
                if(Bytes.compareTo(curr, row) >= 0){
                    seekEd = true;
                    break;
                }
            }
            if(it.hasNext()){
                next = it.next();
            }
            if(!seekEd){//查询数据不再该范围
                curr = null;
                next = null;
            }

        }

        /**
         * init use
         */
        public void seek(){

            if(rowInMem == null || rowInMem.size() == 0) return;

            Set<byte []> rows = rowInMem.keySet();
            it = rows.iterator();
            int count = 1;
            while (it.hasNext()){
                if(count == 1) {
                    curr = it.next();
                }
                if (count == 2){
                    next = it.next();
                    break;
                }
                count ++;
            }
        }

        /**
         * has next row
         * @return
         */
        public boolean hasNext(){
            if(rowInMem == null || rowInMem.size() == 0){
                curr = null;
            }
            return curr == null ? false : true;
        }

        /**
         * return the next row
         * @return
         */

        public Mutation nextRow(){
            Mutation m = rowInMem.get(curr);
            curr = next;
            next = it.hasNext() ? it.next() : null;
            countLeft --;
            return m;
        }


        /**
         * @return max result count left of this scanner
         */
        @Override
        public long getMaxResultsCount() {
            return countLeft;
        }

        /**
         * @return total records' count of this scanner
         */
        @Override
        public long getRecordCount() {
            return rowInMem.size();
        }


        /**
         * @return start key of this scanner
         */
        @Override
        public byte[] getStartKey() {//todo
            return startkey;
        }

        /**
         * return record
         */
        @Override
        public List<Cell> next() {
            List<Cell> cells = new LinkedList<>();
            Mutation m = nextRow();
            try {
                if(m != null){
                    CellScanner scanner = m.cellScanner();
                    while (scanner.advance()){
                        Cell cell = scanner.current();
                        if(match(cell))
                            cells.add(cell);
                    }
                }
            }catch (IOException ioe){
                LOG.error(ioe);
            }

            return cells;
        }

        /**
         * judge whether the cell is accepted by the readSchema
         * @param cell
         * @return
         */
        private boolean match(Cell cell){
            if(filterColumns.isEmpty()) return true;
            else {
                boolean isMatched = false;
                for(byte[] column : filterColumns){
                    if(CellUtil.matchingQualifier(cell, column)) {
                        isMatched = true;
                        return isMatched;
                    }
                }
                return isMatched;
            }
        }

        /**
         * @return end key of this scanner
         */
        @Override
        public byte[] getEndKey() {
            return endkey;
        }


        /**
         * don't iterate just
         *
         * @return first element of the scanner
         */
        @Override
        public List<Cell> peek() {
            if(curr != null) {
                Mutation m = rowInMem.get(curr);
                List<Cell> cells = new LinkedList<>();
                try {
                    if (m != null) {
                        CellScanner scanner = m.cellScanner();
                        while (scanner.advance()) {
                            Cell cell = scanner.current();
                            cells.add(cell);
                        }
                    }
                } catch (IOException ioe) {
                    LOG.error(ioe);
                }
                return cells;
            }else {
                return new LinkedList<>();
            }
        }

        /**
         * Closes this stream and releases any system resources associated
         * with it. If the stream is already closed then invoking this
         * method has no effect.
         *
         * @throws IOException if an I/O error occurs
         */
        @Override
        public void close() throws IOException {
            curr = null;
            next = null;
        }
    }

    public static void main(String []args){
        MessageType schema = MessageTypeParser.parseMessageType( //parquet文件模式
                " message people { " +

                        "required binary rowkey;" +
                        "required binary cf:name;" +
                        "required binary cf:age;" +
                        "required int64 timestamp;"+
                        " }");



    }

}
