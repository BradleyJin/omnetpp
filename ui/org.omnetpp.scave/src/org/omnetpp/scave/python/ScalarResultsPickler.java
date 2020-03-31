package org.omnetpp.scave.python;

import java.io.IOException;
import java.io.OutputStream;

import org.omnetpp.common.Debug;
import org.omnetpp.scave.engine.IDList;
import org.omnetpp.scave.engine.InterruptedFlag;
import org.omnetpp.scave.engine.ResultFileManager;
import org.omnetpp.scave.engine.ScalarResult;

import net.razorvine.pickle.IObjectPickler;
import net.razorvine.pickle.Opcodes;
import net.razorvine.pickle.PickleException;
import net.razorvine.pickle.Pickler;

public class ScalarResultsPickler implements IObjectPickler {

    String filterExpression;
    boolean includeAttrs;
    InterruptedFlag interruptedFlag;

    public ScalarResultsPickler(String filterExpression, boolean includeAttrs, InterruptedFlag interruptedFlag) {
        this.filterExpression = filterExpression;
        this.includeAttrs = includeAttrs;
        this.interruptedFlag = interruptedFlag;
    }

    void pickleScalarResult(ResultFileManager resultManager, long ID, Pickler pickler, OutputStream out)
            throws PickleException, IOException {
        ScalarResult result = resultManager.getScalar(ID);

        // runID, module, name, value
        out.write(Opcodes.MARK);
        {
            pickler.save(result.getRun().getRunName());
            pickler.save(result.getModuleName());
            pickler.save(result.getName());
            pickler.save(result.getValue());
        }
        out.write(Opcodes.TUPLE);
    }

    @Override
    public void pickle(Object obj, OutputStream out, Pickler pickler) throws PickleException, IOException {
        ResultFileManager resultManager = (ResultFileManager)obj;

        out.write(Opcodes.MARK);
        {
            IDList scalars = null;

            out.write(Opcodes.MARK);
            if (filterExpression != null && !filterExpression.trim().isEmpty()) {
                scalars = resultManager.getAllScalars(false);
                scalars = resultManager.filterIDList(scalars, filterExpression, -1, interruptedFlag);

                if (ResultPicklingUtils.debug)
                    Debug.println("pickling " + scalars.size() + " scalars");
                for (int i = 0; i < scalars.size(); ++i) {
                    pickleScalarResult(resultManager, scalars.get(i), pickler, out);
                    if (i % 10 == 0 && interruptedFlag.getFlag())
                        throw new RuntimeException("Result pickling interrupted");
                }
            }
            out.write(Opcodes.LIST);

            if (scalars != null && includeAttrs)
                new ResultAttrsPickler(scalars, interruptedFlag).pickle(resultManager, out, pickler);
            else
                out.write(Opcodes.NONE);
        }
        out.write(Opcodes.TUPLE);
    }
}
