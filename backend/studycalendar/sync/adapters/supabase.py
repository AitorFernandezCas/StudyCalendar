from postgrest.exceptions import APIError
from ..application import SyncUnavailable


class SupabaseSyncGateway:
    def __init__(self, database):
        self.database = database

    def call(self, name, payload):
        try:
            return self.database.rpc(name, payload).execute().data
        except APIError as error:
            if error.code in {'PGRST202', '42883'}:
                raise SyncUnavailable() from error
            raise

    def snapshot(self):
        return self.call('sync_snapshot', {})

    def apply(self, operation):
        return self.call('sync_apply', {'p_operation': operation})
