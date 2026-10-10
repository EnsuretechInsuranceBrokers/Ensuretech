def _payload(**over):
    base = {"email": "new@x.com", "first_name": "N", "last_name": "U", "password": "Str0ng-Passw0rd!x"}
    return {**base, **over}


def test_broker_admin_creates_employer_user_in_own_tenant_only(make_user, login):
    make_user("ba@x.com", "BROKER_ADMIN", broker_id=1)
    c, _ = login("ba@x.com")
    # tries to smuggle a different broker_id; it must be overwritten by the actor's tenant
    r = c.post("/api/v1/users/", _payload(role="EMPLOYER_ADMIN", broker_id=99, employer_id=5),
               format="json")
    assert r.status_code == 201, r.content
    from apps.users.models import User
    u = User.objects.get(email="new@x.com")
    assert (u.broker_id, u.employer_id) == (1, 5)


def test_employer_admin_cannot_create_broker_user(make_user, login):
    make_user("ea@x.com", "EMPLOYER_ADMIN", broker_id=1, employer_id=2)
    c, _ = login("ea@x.com")
    r = c.post("/api/v1/users/", _payload(role="BROKER_ADMIN"), format="json")
    assert r.status_code == 403


def test_non_admin_cannot_create_users(make_user, login):
    make_user("n@x.com", "BROKER_NON_ADMIN", broker_id=1)
    c, _ = login("n@x.com")
    assert c.post("/api/v1/users/", _payload(role="BROKER_NON_ADMIN"), format="json").status_code == 403
