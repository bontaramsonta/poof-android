# End-to-end checklist (real phone)

Run the full list before installing a build that touches the tunnel, the Session or the control plane. Steps 1–3 and 11 are the smoke test for any other build. Source: [Testing strategy](https://github.com/bontaramsonta/poof-android/issues/22).

Check the instance state from the Mac with `aws ec2 describe-instances --region <region> --instance-ids <id>`.

1. **Token:** fresh install; put the token in with `control-plane/token.sh` over `adb`.
2. **Connect:** pick a Country. Both notification steps appear, then Connected.
   - `ifconfig.me` in the browser shows the Exit IP.
   - A DNS leak test shows only Cloudflare, in the Exit's Country.
   - `test-ipv6.com` shows no IPv6, failing fast.
3. **Disconnect in the app:** the instance shows `shutting-down`.
4. **Disconnect from the notification:** same.
5. **Revoke:** switch the VPN off in Settings, or start another VPN app. The app calls `DELETE`; the instance shows `shutting-down`.
6. **Kill and reopen** (`adb shell am force-stop dev.bontaramsonta.poof`): Reconnect/Destroy is offered. Try both paths.
7. **Reboot:** same as step 6.
8. **Network loss:** airplane mode for 1 min, and the tunnel resumes. Then for more than 6 min, and the Exit has terminated itself.
9. **409:** a second `POST /exits` with `curl` brings up the 409 screen. *Terminate it* works.
10. **CLI side by side:** a `poof` CLI Session stays up through every phone action.
11. **Clean finish:** no instance tagged `poof:client=android` runs in any of the 12 regions.
