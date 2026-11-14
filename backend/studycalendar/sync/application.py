import json
from uuid import UUID
from ..shared.domain import BusinessError, User
from .ports import SyncGateway


class SyncUnavailable(Exception):
    """The shared database contract has not been deployed yet."""


class SyncService:
    def __init__(self, gateway: SyncGateway | None, user: User):
        self.gateway = gateway
        self.user = user

    def snapshot(self):
        if self.gateway is None:
            raise SyncUnavailable()
        return self.gateway.snapshot()

    def apply(self, raw):
        if self.gateway is None:
            raise SyncUnavailable()
        if not isinstance(raw, dict) or set(raw) - {'protocol', 'operation_id', 'entity', 'id', 'action', 'base', 'changes', 'context'}:
            raise BusinessError('Operación de sincronización no válida.')
        if raw.get('protocol') != 1:
            raise BusinessError('Actualiza la aplicación para sincronizar.', 'conflict')
        try:
            UUID(raw['operation_id'])
            UUID(raw['id'])
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            raise BusinessError('Los identificadores deben ser UUID.') from error
        if len(json.dumps(raw)) > 262144:
            raise BusinessError('La operación es demasiado grande.')
        return self.gateway.apply(raw)
