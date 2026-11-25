"""Run interactively on a trusted machine, only for your test account."""
import getpass
import os
from instagrapi import Client
from app import data_dir

os.umask(0o077)
username = input('Test account username: ').strip()
password = getpass.getpass('Instagram password: ')
code = getpass.getpass('Current 2FA code (blank if not enabled): ')
client = Client()
client.login(username, password, verification_code=code)
client.dump_settings(str(data_dir() / 'session.json'))
print('Session saved. Treat session.json as a password; never commit or share it.')
