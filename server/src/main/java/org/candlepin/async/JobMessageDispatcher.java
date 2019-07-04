/**
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.async;



/**
 * The JobMessageDispatcher interface defines a method for sending a job message to another
 * queue or underlying messaging system.
 */
public interface JobMessageDispatcher {

    /**
     * Posts a job message to the backing message bus, which may or may not be sent immediately.
     * If the message cannot be posted, this method should throw an exception.
     *
     * @param jobMessage
     *  The JobMessage to post
     *
     * @throws JobMessageDispatchException
     *  if the message cannot be posted for any reason
     */
    void postJobMessage(JobMessage jobMessage) throws JobMessageDispatchException;

    /**
     * Commits any pending messages posted to the backing message bus. If no transaction is
     * active, or no messages have been posted, this method returns silently.
     *
     * @throws JobMessageDispatchException
     *  if the messaging session cannot be committed for any reason
     */
    void commit() throws JobMessageDispatchException;

    /**
     * Rolls back any pending messages posted to the backing message bus that have not yet been
     * committed. If no transaction is active or there are no messages to roll back, this method
     * returns silently.
     *
     * @throws JobMessageDispatchException
     *  if the messaging session cannot be rolled back for any reason
     */
    void rollback() throws JobMessageDispatchException;

}