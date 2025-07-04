package com.github.splitbuddy.service;

import com.github.splitbuddy.converter.GroupMemberConverter;
import com.github.splitbuddy.dao.ExpenseRepository;
import com.github.splitbuddy.dao.ExpenseSplitRepository;
import com.github.splitbuddy.dao.GroupMemberRepository;
import com.github.splitbuddy.dao.GroupRepository;
import com.github.splitbuddy.dtos.*;
import com.github.splitbuddy.entity.*;
import com.github.splitbuddy.enums.NotificationType;
import com.github.splitbuddy.enums.Role;
import com.github.splitbuddy.exception.InvalidDataException;
import com.github.splitbuddy.validation.ExpenseValidationStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.github.splitbuddy.converter.ExpenseConverter.convertToExpense;
import static com.github.splitbuddy.converter.ExpenseSplitConverter.convertToExpenseDTOs;
import static com.github.splitbuddy.converter.ExpenseSplitConverter.convertToExpenseSplit;
import static com.github.splitbuddy.converter.GroupConverter.convertToExpenseDTO;
import static com.github.splitbuddy.converter.GroupConverter.convertToGroupCreationResponse;
import static com.github.splitbuddy.converter.GroupMemberConverter.convertToGroupMemberDTO;
import static com.github.splitbuddy.converter.GroupMemberConverter.createGroupMember;
import static com.github.splitbuddy.enums.NotificationType.EXPENSE_ADDED;
import static com.github.splitbuddy.enums.NotificationType.MEMBER_ADDED;
import static com.github.splitbuddy.utils.SplitUtil.generateUUID;
import static java.lang.String.format;

@Slf4j
@RequiredArgsConstructor
@Service
public class SplitService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ExpenseSplitRepository expenseSplitRepository;
    private final ExpenseRepository expenseRepository;
    private final NotificationService notificationService;

    public GroupCreationResponse createGroup(User user, GroupCreationRequest groupCreationRequest) {
        if (!groupCreationRequest.getMembers().contains(user.getUsername())) {
            groupCreationRequest.getMembers().add(user.getUsername());
        }
        Group group = convertToGroup(groupCreationRequest, user);
        groupRepository.save(group);
        log.info("Group created successfully for group name: {}", groupCreationRequest.getGroupName());
        List<GroupMemberDTO> activeMembers = getGroupMembersAndSendNotification(group);
        return convertToGroupCreationResponse(group, activeMembers);
    }

    private List<GroupMemberDTO> getGroupMembersAndSendNotification(Group group) {

        log.info("Started sending notification to group members");
        return group.getMembers().stream()
                .filter(groupMember -> groupMember.isActive() &&
                        !groupMember.getGroupMemberId().getMemberEmail().equals(group.getCreatedBy().getEmail()))
                .map(groupMember -> {
                    notificationService.notifyUser(NotificationType.GROUP_CREATED, groupMember.getGroupMemberId().getMemberEmail(),
                            group.getName(), group.getCreatedBy().getFullName());
                    return convertToGroupMemberDTO(groupMember);
                })
                .toList();
    }

    private Group convertToGroup(GroupCreationRequest groupCreationRequest, User user) {
        Group group = new Group();
        group.setId(generateUUID());
        group.setName(groupCreationRequest.getGroupName());
        group.setDescription(groupCreationRequest.getDescription());
        group.setCreatedBy(user);
        group.setDeleted(false);
        group.setCreatedAt(new Date());
        group.setUpdatedAt(new Date());
        groupRepository.save(group);
        Set<GroupMember> groupMembers = getGroupMembers(groupCreationRequest, group, user);
        group.setMembers(groupMembers);
        return group;
    }

    private Set<GroupMember> getGroupMembers(GroupCreationRequest groupCreationRequest, Group group, User user) {

        log.info("Creating group members for group id: {}", group.getId());
        return groupCreationRequest.getMembers().stream()
                .map(email -> {
                    GroupMember groupMember = createGroupMember(email, group);
                    groupMember.setAdmin(email.equals(user.getUsername()));
                    groupMemberRepository.save(groupMember);
                    return groupMember;
                })
                .collect(Collectors.toSet());
    }

    public void updateGroupInformation(String loggedInEmail, GroupUpdateRequest groupUpdateRequest) {

       checkIfLoggedInUserIsAdmin(groupUpdateRequest.getGroupId(), loggedInEmail);

        groupRepository.findByIdAndIsDeleted(groupUpdateRequest.getGroupId(), false)
                .map(group -> {
                    group.setName(groupUpdateRequest.getGroupName());
                    group.setDescription(groupUpdateRequest.getDescription());
                    group.setUpdatedAt(new Date());
                    return groupRepository.save(group);
                })
                .orElseThrow(() -> new InvalidDataException("Group not found"));
    }

    public void deleteGroup(String loggedInEmail, String groupId) {

        Group group = checkForActiveGroup(groupId);

        groupMemberRepository.findByGroupIdMemberEmailAndIsActive(groupId, loggedInEmail, true)
                .filter(GroupMember::isAdmin)
                .orElseThrow(() -> new InvalidDataException(format("%s is not an admin", loggedInEmail)));

        group.setDeleted(true);
        group.setUpdatedAt(new Date());
        groupRepository.save(group);
    }

    public GroupExpenseSummary fetchAllGroupSummary(String loggedInEmail) {
        log.info("Fetching all group summary for user: {}", loggedInEmail);
        List<Group> groups = groupRepository.findAllByUserId(loggedInEmail).stream()
                .filter(group -> !group.isDeleted())
                .toList();
        List<GroupExpenseDTO> groupExpenseDTOs = groups.stream()
                .map(group -> convertToGroupExpenseDTO(group, loggedInEmail)).toList();
        double totalSettlementAmount = groupExpenseDTOs.stream()
                .mapToDouble(GroupExpenseDTO::getSettlementAmount).sum();
        return new GroupExpenseSummary(totalSettlementAmount, groupExpenseDTOs);
    }

    public GroupExpenseDTO convertToGroupExpenseDTO(Group group, String loggedInEmail) {

        try {
            List<Expense> expenses = expenseRepository.findAllExpensesWithSplits(group.getId());

            GroupExpenseDTO groupExpenseDTO = convertToExpenseDTO(group);
            groupExpenseDTO.setSettlementAmount(calculateSettlementAmount(expenses, loggedInEmail));
            groupExpenseDTO.setMembers(getGroupMemberDTO(group.getMembers()));
            return groupExpenseDTO;
        }  catch (Exception e) {
            log.error("Error while fetching group expense summary", e);
            throw new InvalidDataException("Invalid data");
        }
    }

    private List<GroupMemberDTO> getGroupMemberDTO(Set<GroupMember> members) {
        return members.stream()
                .filter(GroupMember::isActive)
                .map(GroupMemberConverter::convertToGroupMemberDTO).toList();
    }

    public void addExpenseToGroup(User user, String groupId, ExpenseCreationRequest request) {
        log.info("Adding expense to group id: {}", groupId);
        Group group = checkForActiveGroup(groupId);

        validateExpenseCreationRequest(user, group, request);

        ExpenseValidationStrategyFactory.getStrategy(request.getSplitType())
                .validate(request);

        Expense expense = convertToExpense(group, request, user);
        expenseRepository.save(expense);
        request.getShares().forEach(share -> {
            ExpenseSplit split = convertToExpenseSplit(share, expense);
            expenseSplitRepository.save(split);
            if (share.amountOwed() > 0) {
                notificationService.notifyUser(EXPENSE_ADDED, share.owedBy(), share.owedBy().split("@")[0],
                        share.amountOwed(), group.getName());
            }
        });
    }

    private void validateExpenseCreationRequest(User user, Group group, ExpenseCreationRequest request) {
        log.info("Validating expense creation request for user: {}", user.getUsername());
        boolean isMember = group.getMembers().stream()
                .anyMatch(member ->
                        member.getGroupMemberId().getMemberEmail().equals(user.getUsername()) && member.isActive());

        if (!isMember) {
            throw new InvalidDataException("User is not a member of the group");
        }

        Set<String> groupMemberEmails = group.getMembers().stream()
                .filter(GroupMember::isActive)
                .map(member -> member.getGroupMemberId().getMemberEmail())
                .collect(Collectors.toSet());

        for (IndividualShare share : request.getShares()) {
            if (!groupMemberEmails.contains(share.owedBy())) {
                throw new InvalidDataException("User " + share.owedBy() + " is not a member of this group");
            }
        }

        if (!groupMemberEmails.contains(request.getPaidBy())) {
            throw new InvalidDataException("Payer is not a member of the group");
        }
    }

    public void deleteGroupMember(String loggerInEmail, String groupId, String memberEmail) {
        log.info("Deleting group member: {} from group: {}", memberEmail, groupId);
        checkForActiveGroup(groupId);

        checkIfLoggedInUserIsAdmin(groupId, loggerInEmail);

        GroupMember groupMember = groupMemberRepository.findByGroupIdMemberEmailAndIsActive(groupId, memberEmail, true)
                .orElseThrow(() -> new InvalidDataException(format("%s is not an active member", memberEmail)));

        groupMember.setActive(false);
        groupMemberRepository.save(groupMember);
    }

    public void addMemberToGroup(User user, AddGroupMemberRequest addGroupMemberRequest) {
        log.info("Adding member: {} to group: {}", addGroupMemberRequest.getMemberEmail(), addGroupMemberRequest.getGroupId());
        Group group = checkForActiveGroup(addGroupMemberRequest.getGroupId());

        checkIfLoggedInUserIsAdmin(group.getId(), user.getUsername());
        Optional<GroupMember> groupMember = groupMemberRepository.findByGroupIdMemberEmailAndIsActive(addGroupMemberRequest.getGroupId(),
                addGroupMemberRequest.getMemberEmail(), true);
        if (groupMember.isPresent()) {
            throw new InvalidDataException("Member already exists");
        }
        group.getMembers().stream()
                .filter(member -> member.getGroupMemberId().getMemberEmail().equals(addGroupMemberRequest.getMemberEmail())
                        && !member.isActive())
                .findAny()
                .ifPresentOrElse(member -> {
                    member.setActive(true);
                    member.setAdmin(addGroupMemberRequest.getRole() != null && addGroupMemberRequest.getRole() == Role.ADMIN);
                    groupMemberRepository.save(member);
                }, () -> {
                    final var newGroupMember = createGroupMember(addGroupMemberRequest.getMemberEmail(), group);
                    newGroupMember.setAdmin(addGroupMemberRequest.getRole() != null && addGroupMemberRequest.getRole() == Role.ADMIN);
                    groupMemberRepository.save(newGroupMember);
                });

        log.info("Notifying member: {}", addGroupMemberRequest.getMemberEmail());
        notificationService.notifyUser(MEMBER_ADDED, addGroupMemberRequest.getMemberEmail(),
                addGroupMemberRequest.getMemberEmail().split("@")[0], group.getName(), user.getFullName());

    }

    public double calculateSettlementAmount(List<Expense> expenses, String userEmail) {
        log.info("Calculating settlement amount for user: {}", userEmail);
        double settlementAmount = 0.0;
        final var transactions = getLoggedInUserSettlements(expenses, userEmail);
        for (final var transaction : transactions) {
            if (transaction.getFromUser().equals(userEmail)) {
                settlementAmount -= transaction.getAmount();
            } else if (transaction.getToUser().equals(userEmail)) {
                settlementAmount += transaction.getAmount();
            }
        }
        return settlementAmount;
    }

    public Map<String, Double> calculateNetBalances(List<Expense> expenses) {
        log.info("Calculating net balances");
        Map<String, Double> balances = new HashMap<>();

        for (Expense expense : expenses) {
            String payer = expense.getPaidBy();
            double paidAmount = expense.getTotalAmount();

            balances.put(payer, balances.getOrDefault(payer, 0.0) + paidAmount);

            for (ExpenseSplit split : expense.getSplits()) {

                String debtor = split.getOwedBy();
                double owedAmount = split.getAmountOwed();

                balances.put(debtor, balances.getOrDefault(debtor, 0.0) - owedAmount);
            }
        }

        return balances;
    }

    public List<SettlementTransactionDTO> minimizeTransactions(Map<String, Double> balances) {
        log.info("Minimizing transactions");
        PriorityQueue<Balance> creditors = new PriorityQueue<>((a, b) -> Double.compare(b.getAmount(), a.getAmount()));
        PriorityQueue<Balance> debtors = new PriorityQueue<>((a, b) -> Double.compare(b.getAmount(), a.getAmount()));

        for (Map.Entry<String, Double> entry : balances.entrySet()) {
            if (entry.getValue() > 0) {
                creditors.offer(new Balance(entry.getKey(), entry.getValue()));
            } else if (entry.getValue() < 0) {
                debtors.offer(new Balance(entry.getKey(), -entry.getValue()));
            }
        }

        List<SettlementTransactionDTO> transactions = new ArrayList<>();

        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Balance creditor = creditors.poll();
            Balance debtor = debtors.poll();

            assert debtor != null;
            double settleAmount = Math.min(creditor.getAmount(), debtor.getAmount());
            String from = debtor.getEmail();
            String to = creditor.getEmail();
            transactions.add(new SettlementTransactionDTO(from, to, settleAmount));

            if (creditor.getAmount() > settleAmount) {
                creditors.offer(new Balance(creditor.getEmail(), creditor.getAmount() - settleAmount));
            }
            if (debtor.getAmount() > settleAmount) {
                debtors.offer(new Balance(debtor.getEmail(), debtor.getAmount() - settleAmount));
            }
        }
        return transactions;
    }

    public List<SettlementTransactionDTO> getLoggedInUserSettlements(List<Expense> expenses, String userEmail) {
        log.info("Getting logged in user settlements for user email : {}", userEmail);
        Map<String, Double> balances = calculateNetBalances(expenses);

        List<SettlementTransactionDTO> allTransactions = minimizeTransactions(balances);

        return allTransactions.stream()
                .filter(tx -> tx.getFromUser().equals(userEmail) || tx.getToUser().equals(userEmail))
                .toList();
    }

    public List<SettlementTransactionDTO> getAllSettlements(String loggedInEmail, String groupId) {
        log.info("Getting all settlements for group: {}", groupId);
        Group group = checkForActiveGroup(groupId);
        groupMemberRepository.findByGroupIdMemberEmailAndIsActive(groupId, loggedInEmail, true)
                .orElseThrow(() -> new InvalidDataException("User is not an admin"));
        List<Expense> expenses = expenseRepository.findAllExpensesWithSplits(group.getId());
        return getLoggedInUserSettlements(expenses, loggedInEmail);
    }

    public GroupExpenseDTO getGroupInformation(String currentUserEmail, String groupId) {
        log.info("Getting group information for group: {}", groupId);
        Group group = checkForActiveGroup(groupId);

        groupMemberRepository.findByGroupIdMemberEmailAndIsActive(groupId, currentUserEmail, true)
                .orElseThrow(() -> new InvalidDataException("User is not an active user"));

        List<Expense> expenses = expenseRepository.findAllExpensesWithSplits(group.getId());
        GroupExpenseDTO groupExpenseDTO = convertToExpenseDTO(group);
        groupExpenseDTO.setExpenseSplits(convertToExpenseDTOs(expenses));
        return groupExpenseDTO;
    }

    public void checkIfLoggedInUserIsAdmin(String groupId, String email) {
        groupMemberRepository.findByGroupIdMemberEmailAndIsActive(groupId, email, true)
                .filter(GroupMember::isAdmin)
                .orElseThrow(() -> new InvalidDataException("User is not present/user is verified"));
    }

    public Group checkForActiveGroup(String groupId) {
        return groupRepository.findByIdAndIsDeleted(groupId, false)
                .orElseThrow(() -> new InvalidDataException("Group not found"));
    }
}
