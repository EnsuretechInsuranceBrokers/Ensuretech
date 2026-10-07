from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone

from apps.users import service_accounts as sa
from apps.users.models import ServiceAccount


class Command(BaseCommand):
    help = "Manage machine identities: create | rotate | revoke | list. Secrets are shown once and never stored."

    def add_arguments(self, parser):
        sub = parser.add_subparsers(dest="action", required=True)
        c = sub.add_parser("create"); c.add_argument("name"); c.add_argument("--description", default="")
        r = sub.add_parser("rotate"); r.add_argument("name"); r.add_argument("--grace-minutes", type=int, default=0)
        v = sub.add_parser("revoke"); v.add_argument("name")
        sub.add_parser("list")

    def handle(self, *args, action, **o):
        if action == "list":
            for a in ServiceAccount.objects.order_by("name"):
                self.stdout.write(f"{a.name}\t{a.client_id}\t{'active' if a.is_active else 'REVOKED'}\tlast used {a.last_used_at or 'never'}")
            return
        name = o["name"]
        if action == "create":
            if ServiceAccount.objects.filter(name=name).exists():
                raise CommandError(f"{name} already exists")
            acct, secret = sa.create_account(name, o["description"])
            self._show(acct, secret)
            return
        try:
            acct = ServiceAccount.objects.get(name=name)
        except ServiceAccount.DoesNotExist:
            raise CommandError(f"no such service account: {name}")
        if action == "rotate":
            self._show(acct, sa.rotate(acct, o["grace_minutes"]))
        elif action == "revoke":
            acct.is_active = False
            acct.save(update_fields=["is_active"])
            acct.credentials.filter(revoked_at__isnull=True).update(revoked_at=timezone.now())
            self.stdout.write(f"{name} revoked. Tokens already issued stay valid until they expire (<= SERVICE_TOKEN_MINUTES).")

    def _show(self, acct, secret):
        self.stdout.write(f"SERVICE_CLIENT_ID={acct.client_id}\nSERVICE_CLIENT_SECRET={secret}\n(this secret is not shown again)")
