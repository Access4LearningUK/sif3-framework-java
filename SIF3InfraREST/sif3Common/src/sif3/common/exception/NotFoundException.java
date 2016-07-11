/*
 * Crown Copyright � Department for Education (UK) 2016
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package sif3.common.exception;

import javax.ws.rs.core.Response.Status;

public class NotFoundException extends SIF3Exception
{
    private static final long serialVersionUID = 1409571837341328926L;

    public NotFoundException()
    {
        super();
    }

    public NotFoundException(String msg)
    {
        super(msg);
    }

    public NotFoundException(String msg, Throwable ex)
    {
        super(msg, ex);
    }

    public NotFoundException(Throwable ex)
    {
        super(ex);
    }

    @Override
    public int getStatus()
    {
        return Status.NOT_FOUND.getStatusCode();
    }
}
