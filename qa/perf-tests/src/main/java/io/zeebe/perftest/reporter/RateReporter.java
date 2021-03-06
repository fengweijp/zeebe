/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.perftest.reporter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class RateReporter
{
    protected AtomicLong value = new AtomicLong(0);

    protected long lastReportedValue = 0;
    protected long startTime = 0;

    protected final long reportIntervalInNs;

    protected final RateReportFn reportFn;

    protected volatile boolean exit = false;

    public RateReporter(int interval, TimeUnit intervalUnit, RateReportFn reportFn)
    {
        this.reportFn = reportFn;
        this.reportIntervalInNs = intervalUnit.toNanos(interval);
        this.startTime = System.nanoTime();
    }

    public void doReport()
    {
        do
        {
            final long now = System.nanoTime();
            final long currentValue = value.get();
            final long intervalValue = currentValue - lastReportedValue;
            final long timestamp = now - startTime;

            reportFn.reportRate(timestamp, intervalValue);

            lastReportedValue = currentValue;

            LockSupport.parkNanos(reportIntervalInNs);
        }
        while (!exit);
    }

    public void increment()
    {
        value.incrementAndGet();
    }

    public void exit()
    {
        exit = true;
    }

}
